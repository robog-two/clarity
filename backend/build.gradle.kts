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
    implementation(libs.guava) // Public Suffix List (InternetDomainName) for vault grants
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
    // The decision model's live shape check (LiveDecisionTest, OpenRouter, synthetic text, three calls): -Pclarity.liveDecisions.
    if (providers.gradleProperty("clarity.liveDecisions").isPresent) systemProperty("clarity.liveDecisions", "true")
    // The OpenRouter replay of real journal points (ReplayFeasibilityTest): -Pclarity.replay=<points.json>, -Pclarity.replayOut=<results.json>.
    providers.gradleProperty("clarity.replay").orNull?.let { systemProperty("clarity.replay", it) }
    providers.gradleProperty("clarity.replayOut").orNull?.let { systemProperty("clarity.replayOut", it) }
    if (providers.gradleProperty("clarity.replayDry").isPresent) systemProperty("clarity.replayDry", "true")
}
