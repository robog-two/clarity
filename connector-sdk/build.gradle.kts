plugins {
    alias(libs.plugins.clarity.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(projects.shared)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.cio)
    api(libs.kotlin.logging)
    api(libs.arrow.core)
    implementation(libs.tink)
    testFixturesApi(projects.shared)
    testFixturesImplementation(libs.tink)

    testImplementation(libs.ktor.client.mock)
}
