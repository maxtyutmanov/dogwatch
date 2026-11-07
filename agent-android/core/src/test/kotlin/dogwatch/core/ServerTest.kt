package dogwatch.core

import dogwatch.core.auth.InMemorySecretStore
import dogwatch.core.auth.PairingManager
import dogwatch.core.auth.RateLimiter
import dogwatch.core.model.BatteryStatus
import dogwatch.core.model.CameraStatus
import dogwatch.core.model.DeviceInfo
import dogwatch.core.model.DeviceStatus
import dogwatch.core.model.NetworkStatus
import dogwatch.core.server.AgentContext
import dogwatch.core.server.DogwatchJson
import dogwatch.core.server.SESSION_COOKIE
import dogwatch.core.server.WebAssets
import dogwatch.core.server.dogwatchModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val status = DeviceStatus(
        device = DeviceInfo("Acme", "Phone", "13", 33),
        battery = BatteryStatus(90, true, 30.5),
        network = NetworkStatus(true, -50, "100.64.0.2", true),
        storageFreeMb = 100,
        deviceUptimeSec = 10,
        agentUptimeSec = 5,
        permissions = mapOf("camera" to true),
        camera = CameraStatus("idle"),
        agentVersion = "test",
    )
    private val pairing = PairingManager(InMemorySecretStore())
    private val ctx = AgentContext(
        pairing = pairing,
        status = { status },
        web = WebAssets { path ->
            mapOf("index.html" to "<html>ui</html>", "assets/app.js" to "console.log(1)")[path]?.toByteArray()
        },
        version = "test",
        rateLimiter = RateLimiter(maxFailures = 3),
    )

    /** Ktor's test client sends no Host header; real browsers always do. */
    private fun ApplicationTestBuilder.browserLikeClient(withWebSockets: Boolean = false) = createClient {
        if (withWebSockets) install(WebSockets)
        defaultRequest { header(HttpHeaders.Host, "localhost") }
    }

    private fun app(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { dogwatchModule(ctx) }
        block(browserLikeClient())
    }

    private suspend fun HttpClient.pair(token: String): HttpResponse = post("/api/auth/pair") {
        contentType(ContentType.Application.Json)
        setBody("""{"token":"$token"}""")
    }

    private fun HttpResponse.sessionCookie(): String {
        val setCookie = headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("$SESSION_COOKIE=") }
        assertTrue(setCookie.contains("HttpOnly"), setCookie)
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie)
        return setCookie.substringAfter("=").substringBefore(";")
    }

    @Test
    fun missingHostHeaderIsRejected() = testApplication {
        application { dogwatchModule(ctx) }
        assertEquals(421, client.get("/").status.value)
    }

    @Test
    fun apiRequiresSession() = app { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=forged") }.status)
    }

    @Test
    fun pairingGrantsSession() = app { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.pair("wrong").status)
        val res = client.pair(pairing.pairingToken())
        assertEquals(HttpStatusCode.OK, res.status)
        val cookie = res.sessionCookie()

        val statusRes = client.get("/api/status") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }
        assertEquals(HttpStatusCode.OK, statusRes.status)
        assertEquals(status, DogwatchJson.decodeFromString(DeviceStatus.serializer(), statusRes.bodyAsText()))

        val sessions = client.get("/api/auth/sessions") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }
        assertTrue(sessions.bodyAsText().contains("\"id\""))

        // Logout revokes the session.
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/api/auth/logout") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }.status)
    }

    @Test
    fun revokeOtherSession() = app { client ->
        val a = client.pair(pairing.pairingToken()).sessionCookie()
        val b = client.pair(pairing.pairingToken()).sessionCookie()
        val bId = pairing.authenticate(b)!!.id
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/auth/sessions/$bId") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$a") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$b") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/auth/sessions/$bId") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$a") }.status)
    }

    @Test
    fun pairingIsRateLimited() = app { client ->
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.pair("wrong").status) }
        val locked = client.pair(pairing.pairingToken())
        assertEquals(HttpStatusCode.TooManyRequests, locked.status, "even the right token is refused while locked out")
        assertNotNull(locked.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun rejectsForeignHostAndOrigin() = app { client ->
        val token = pairing.pairingToken()
        val rebinding = client.get("/api/status") { header(HttpHeaders.Host, "evil.example.com") }
        assertEquals(421, rebinding.status.value)

        val csrf = client.post("/api/auth/pair") {
            header(HttpHeaders.Origin, "http://evil.example.com")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, csrf.status)

        val sameOrigin = client.post("/api/auth/pair") {
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status)
    }

    @Test
    fun malformedPairBodyIs400() = app { client ->
        val res = client.post("/api/auth/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun servesWebUiWithSpaFallback() = app { client ->
        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertEquals("<html>ui</html>", index.bodyAsText())
        assertNotNull(index.headers["Content-Security-Policy"])
        assertEquals("<html>ui</html>", client.get("/status").bodyAsText(), "client-side route falls back to index")
        val js = client.get("/assets/app.js")
        assertTrue(js.headers[HttpHeaders.CacheControl]!!.contains("immutable"))
        assertEquals(HttpStatusCode.NotFound, client.get("/missing.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/../secret.txt").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/nope").status)
    }

    @Test
    fun eventsWebSocketSendsStatusThenPublishedEvents() = app { client ->
        val cookie = client.pair(pairing.pairingToken()).sessionCookie()
        val ws = browserLikeClient(withWebSockets = true)
        ws.webSocket("/api/events", request = { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }) {
            val first = DogwatchJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("status", first["type"]!!.jsonPrimitive.content)

            ctx.bus.publish("test.ping", buildJsonObject { put("n", 1) })
            val second = DogwatchJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("test.ping", second["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun auditLogRecordsApiCalls() = app { client ->
        val cookie = client.pair(pairing.pairingToken()).sessionCookie()
        client.get("/api/status") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie") }
        client.get("/api/status")
        val entries = ctx.audit.recent(10)
        assertEquals(listOf(401, 200, 200), entries.take(3).map { it.status })
        assertEquals("/api/status", entries[0].path)
        assertEquals(null, entries[0].session)
        assertNotNull(entries[1].session)
    }
}
