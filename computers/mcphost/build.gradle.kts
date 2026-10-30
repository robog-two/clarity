plugins {
    id("clarity.kotlin-jvm")
    application
}

application {
    mainClass.set("net.robog.clarity.mcphost.MainKt")
    applicationName = "mcphost"
    // Small and steady: one supervisor and a few child processes. Basic proxy auth has to be allowed
    // for CONNECT tunnels (the JDK refuses it by default); those credentials only go to the egress proxy.
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC", "-Xms8m", "-Xmx64m", "-Xss512k",
        "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=24m", "-XX:TieredStopAtLevel=1",
        "-XX:+ExitOnOutOfMemoryError",
        "-Djdk.http.auth.tunneling.disabledSchemes=", "-Djdk.http.auth.proxying.disabledSchemes=",
    )
}

dependencies {
    implementation(projects.connectorSdk)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    testImplementation(testFixtures(projects.connectorSdk))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mcp.kotlin.sdk)
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.ktor.client.cio)
}

// The fake MCP server the tests start is a Python script; its path reaches the tests as a property.
tasks.withType<Test>().configureEach {
    systemProperty("mcphost.fakeServer", layout.projectDirectory.file("src/test/resources/fake_mcp.py").asFile.absolutePath)
}
