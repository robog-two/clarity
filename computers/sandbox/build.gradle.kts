plugins {
    id("clarity.kotlin-jvm")
    application
}

application {
    mainClass.set("net.robog.clarity.sandbox.MainKt")
    applicationName = "sandboxd"
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError")
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.jsoup)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.ktor.client.cio)
}
