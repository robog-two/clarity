// Android app: a separate Gradle build so the server/connector builds never need the Android SDK.
// :shared comes from the root build via a composite include (substituted for net.robog.clarity:shared).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                // Tink is published on Maven Central only.
                excludeGroup("com.google.crypto.tink")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                // Tink is published on Maven Central only.
                excludeGroup("com.google.crypto.tink")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // On-device speech recognition (sherpa-onnx, Apache-2.0): the official prebuilt AAR from the
        // project's GitHub release. It is on no Maven repository, so this repo serves that one module
        // and nothing else; its SHA-256 is pinned in gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpaOnnxReleases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx-static-link-onnxruntime") }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "clarity-android"

include(":app")

includeBuild("..") {
    dependencySubstitution {
        substitute(module("net.robog.clarity:shared")).using(project(":shared"))
    }
}
