plugins {
    alias(libs.plugins.clarity.kotlin.jvm)
    `java-library`
}

dependencies {
    api(projects.shared)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.cio)
    api(libs.kotlin.logging)
    api(libs.arrow.core)

    testImplementation(libs.ktor.client.mock)
}
