import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent part of the agent: HTTP/WebSocket server, auth, events, models.
// Pure JVM so it can be unit-tested and run as a dev server without a phone.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.ktor.server.core)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

// `./gradlew :core:run` starts a dev server with fake device data, serving ../../web/dist.
application {
    mainClass.set("dogwatch.core.dev.DevServerKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // slf4j-simple only for the dev server; Android uses its own binding.
    classpath += configurations.testRuntimeClasspath.get()
}
