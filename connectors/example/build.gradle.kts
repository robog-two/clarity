plugins {
    alias(libs.plugins.clarity.jvm.app)
}

application {
    mainClass.set("net.robog.clarity.connector.example.MainKt")
    applicationName = "clarity-connector-example"
}

dependencies {
    implementation(projects.connectorSdk)
}
