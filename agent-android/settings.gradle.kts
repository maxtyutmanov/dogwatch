pluginManagement {
    // Maven Central first: everything :core needs lives there, so it builds even where Google's
    // repository is unreachable.
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    plugins {
        // Declared here (not in the root build script) so that the Android Gradle Plugin is only
        // resolved when the :app project is actually included. This lets :core build and test
        // on machines without the Android SDK.
        id("com.android.application") version "8.12.0"
        id("org.jetbrains.kotlin.android") version "2.4.21"
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "dogwatch-agent"

include(":core")

// :app needs the Android SDK. Include it only when one is configured, so plain-JVM machines can
// still run `./gradlew :core:test` and the dev server.
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasAndroidSdk = listOf(
    localProps.getProperty("sdk.dir"),
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
).any { !it.isNullOrBlank() && file(it).isDirectory }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found: building :core only (set ANDROID_HOME to include :app).")
}
