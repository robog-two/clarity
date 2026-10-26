plugins {
    alias(libs.plugins.clarity.jvm.app)
}

application {
    mainClass.set("net.robog.clarity.backend.MainKt")
    applicationName = "clarity-backend"
}

dependencies {
    implementation(projects.shared)

    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client) // MCP client transport
    implementation(libs.ktor.client.cio)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.persistence)
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)
    implementation(libs.kotlinx.coroutines.core)

    // Agent harness (net.robog.clarity.harness)
    implementation(libs.anthropic.java)
    implementation(libs.mcp.kotlin.sdk)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
}

// Live evals (real Anthropic API, costs money) run only on request: -Pclarity.liveEvals. Never in CI.
tasks.test {
    if (providers.gradleProperty("clarity.liveEvals").isPresent) systemProperty("clarity.liveEvals", "true")
}
