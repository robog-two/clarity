// Root build: shared contracts + JVM services. The Android app lives in its own Gradle
// build under android/ (it includes this build for :shared), so server and connector
// builds never need the Android SDK.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // dav4jvm is only published on JitPack; nothing else may come from there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.bitfireAT") }
        }
    }
}

rootProject.name = "clarity"

include(
    ":shared",
    ":backend",
    ":connector-sdk",
    ":connectors:example",
    ":connectors:mail",
    ":connectors:dav",
    ":computers:sandbox",
)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
