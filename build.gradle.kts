plugins {
    // Load plugins once on the root classpath to avoid classloader duplication.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.metro) apply false
}

allprojects {
    group = "net.robog.clarity"
    version = providers.gradleProperty("clarity.version").getOrElse("0.1.0-SNAPSHOT")
}
