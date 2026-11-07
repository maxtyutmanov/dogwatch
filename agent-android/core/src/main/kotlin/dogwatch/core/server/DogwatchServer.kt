package dogwatch.core.server

import dogwatch.core.audit.AuditEntry
import dogwatch.core.auth.SessionInfo
import dogwatch.core.events.Event
import dogwatch.core.model.DeviceStatus
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.Frame
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

const val SESSION_COOKIE = "dw_session"
private val SessionKey = AttributeKey<SessionInfo>("dogwatch.session")

@Serializable
data class PairRequest(val token: String)

@Serializable
data class ApiError(val error: String, val retryAfterSec: Long? = null)

/** Paths reachable without a session. Everything else under /api requires one. */
private val PUBLIC_API_PATHS = setOf("/api/auth/pair")

/** Wraps the Ktor engine. The Android service calls [start]/[stop]. */
class AgentServer(private val ctx: AgentContext, private val port: Int = DEFAULT_PORT) {
    private var engine: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { dogwatchModule(ctx) }.start(wait = false)
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

fun Application.dogwatchModule(ctx: AgentContext) {
    install(ContentNegotiation) { json(DogwatchJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
    }
    install(StatusPages) {
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        }
    }

    // Audit every API call, after the response status is known.
    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            proceed()
        } finally {
            val path = call.request.path()
            if (path.startsWith("/api/")) {
                ctx.audit.record(
                    AuditEntry(
                        at = System.currentTimeMillis(),
                        session = call.attributes.getOrNull(SessionKey)?.id,
                        remoteAddress = call.request.origin.remoteAddress,
                        method = call.request.httpMethod.value,
                        path = path,
                        status = call.response.status()?.value ?: 0,
                    ),
                )
            }
        }
    }

    // Network, Host, Origin and session checks, in that order.
    intercept(ApplicationCallPipeline.Plugins) {
        val req = call.request
        if (!RequestGuards.isAllowedInterface(req.local.localAddress, ctx.allowLan())) {
            call.respond(HttpStatusCode.Forbidden, ApiError("interface_not_allowed"))
            return@intercept finish()
        }
        val hostHeader = req.header(HttpHeaders.Host)
        if (!RequestGuards.isAllowedHost(hostHeader)) {
            call.respond(HttpStatusCode(421, "Misdirected Request"), ApiError("host_not_allowed"))
            return@intercept finish()
        }
        val isWebSocket = req.header(HttpHeaders.Upgrade).equals("websocket", ignoreCase = true)
        val unsafe = req.httpMethod != HttpMethod.Get && req.httpMethod != HttpMethod.Head
        if ((unsafe || isWebSocket) && !RequestGuards.isAllowedOrigin(req.header(HttpHeaders.Origin), hostHeader)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("origin_not_allowed"))
            return@intercept finish()
        }
        val path = req.path()
        if (path.startsWith("/api/") && path !in PUBLIC_API_PATHS) {
            val session = ctx.pairing.authenticate(req.cookies[SESSION_COOKIE, CookieEncoding.RAW])
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                return@intercept finish()
            }
            call.attributes.put(SessionKey, session)
        }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Frame-Options", "DENY")
    }

    routing {
        route("/api") {
            post("/auth/pair") { pair(ctx) }

            get("/auth/me") { call.respond(call.attributes[SessionKey]) }

            post("/auth/logout") {
                ctx.pairing.revoke(call.attributes[SessionKey].id)
                call.response.cookies.append(sessionCookie("", maxAgeSec = 0))
                call.respond(HttpStatusCode.NoContent)
            }

            get("/auth/sessions") { call.respond(ctx.pairing.sessions()) }

            delete("/auth/sessions/{id}") {
                val id = call.parameters["id"].orEmpty()
                if (ctx.pairing.revoke(id)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
            }

            get("/status") { call.respond(ctx.status.current()) }

            get("/audit") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                call.respond(ctx.audit.recent(limit))
            }

            webSocket("/events") {
                // Subscribe first, then send the initial status, so nothing published in between is lost.
                val forward = launch {
                    ctx.bus.events.collect { send(Frame.Text(DogwatchJson.encodeToString(Event.serializer(), it))) }
                }
                val initial = Event(
                    type = "status",
                    ts = System.currentTimeMillis(),
                    data = DogwatchJson.encodeToJsonElement(DeviceStatus.serializer(), ctx.status.current()),
                )
                send(Frame.Text(DogwatchJson.encodeToString(Event.serializer(), initial)))
                try {
                    for (frame in incoming) { /* viewers don't send anything yet */ }
                } finally {
                    forward.cancel()
                }
            }

            route("{...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiError("not_found")) }
            }
        }

        get("/") { serveAsset(ctx, "index.html") }
        get("/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (segments.any { it == ".." || it == "." || it.contains('\\') }) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            serveAsset(ctx, segments.joinToString("/"))
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.pair(ctx: AgentContext) {
    val remote = call.request.origin.remoteAddress
    val wait = ctx.rateLimiter.retryAfterMs(remote)
    if (wait > 0) {
        val sec = (wait + 999) / 1000
        call.response.header(HttpHeaders.RetryAfter, sec.toString())
        call.respond(HttpStatusCode.TooManyRequests, ApiError("too_many_attempts", sec))
        return
    }
    val body = call.receive<PairRequest>()
    if (!ctx.pairing.verifyPairingToken(body.token)) {
        ctx.rateLimiter.recordFailure(remote)
        call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_token"))
        return
    }
    ctx.rateLimiter.recordSuccess(remote)
    val (secret, info) = ctx.pairing.createSession(call.request.header(HttpHeaders.UserAgent).orEmpty(), remote)
    call.attributes.put(SessionKey, info)
    call.response.cookies.append(sessionCookie(secret, maxAgeSec = 400 * 24 * 3600))
    call.respond(info)
}

private fun sessionCookie(value: String, maxAgeSec: Int) = Cookie(
    name = SESSION_COOKIE,
    value = value,
    encoding = CookieEncoding.RAW,
    maxAge = maxAgeSec,
    path = "/",
    httpOnly = true,
    // Plain HTTP inside the WireGuard tunnel, so no `Secure` flag (see DESIGN.md §7).
    secure = false,
    extensions = mapOf("SameSite" to "Strict"),
)

private suspend fun io.ktor.server.routing.RoutingContext.serveAsset(ctx: AgentContext, path: String) {
    val direct = ctx.web.read(path)
    val isSpaRoute = !path.substringAfterLast('/').contains('.')
    val (bytes, served) = when {
        direct != null -> direct to path
        isSpaRoute -> (ctx.web.read("index.html") ?: return call.respond(HttpStatusCode.NotFound)) to "index.html"
        else -> return call.respond(HttpStatusCode.NotFound)
    }
    call.response.header(
        HttpHeaders.CacheControl,
        if (served.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache",
    )
    if (served == "index.html") {
        // Host was validated by RequestGuards.isAllowedHost. Listed explicitly because older Safari
        // doesn't treat ws:// as matching 'self'.
        val host = call.request.header(HttpHeaders.Host).orEmpty()
        call.response.header("Content-Security-Policy", "$CSP; connect-src 'self' ws://$host wss://$host")
    }
    call.respondBytes(bytes, contentTypeFor(served))
}

private const val CSP = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; " +
    "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; manifest-src 'self'"

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html
    "js", "mjs" -> ContentType.Application.JavaScript
    "css" -> ContentType.Text.CSS
    "json", "webmanifest" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "ico" -> ContentType("image", "x-icon")
    "woff2" -> ContentType("font", "woff2")
    else -> ContentType.Application.OctetStream
}
