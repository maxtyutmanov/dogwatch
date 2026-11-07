import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dogwatch.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.maxtyutmanov.dogwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    // The web UI (built from /web) is bundled under assets/web/.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    runtimeOnly(libs.slf4j.nop)
}

// ---- Web UI build -----------------------------------------------------------------------------
// Pass -PskipWebBuild to reuse an existing web/dist (e.g. when iterating on Kotlin only).

val webDir = rootProject.projectDir.resolve("../web")

val npmCi by tasks.registering(Exec::class) {
    workingDir = webDir
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file(webDir.resolve("package-lock.json"))
    outputs.dir(webDir.resolve("node_modules"))
    onlyIf { !project.hasProperty("skipWebBuild") }
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir = webDir
    commandLine("npm", "run", "build")
    inputs.dir(webDir.resolve("src"))
    inputs.dir(webDir.resolve("public"))
    inputs.files(webDir.resolve("index.html"), webDir.resolve("vite.config.ts"), webDir.resolve("package-lock.json"))
    outputs.dir(webDir.resolve("dist"))
    onlyIf { !project.hasProperty("skipWebBuild") }
}

val copyWebAssets by tasks.registering(Sync::class) {
    dependsOn(npmBuild)
    from(webDir.resolve("dist"))
    into(layout.buildDirectory.dir("generated/webAssets/web"))
}

tasks.named("preBuild") { dependsOn(copyWebAssets) }
