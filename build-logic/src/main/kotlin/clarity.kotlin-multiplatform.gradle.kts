// Kotlin Multiplatform library shared between Android and the JVM services.
// Only a `jvm()` target for now: Android consumes the JVM variant directly, which keeps
// the server/connector builds free of the Android SDK. Add `android { }` / iOS targets
// here when platform-specific code is actually needed.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(catalog.version("sharedJvmTarget"))) }
    }
    compilerOptions {
        optIn.addAll("kotlin.time.ExperimentalTime")
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = catalog.version("sharedJvmTarget")
    targetCompatibility = catalog.version("sharedJvmTarget")
}
