plugins {
    alias(libs.plugins.clarity.jvm.app)
}

application {
    mainClass.set("net.robog.clarity.connector.mail.MainKt")
    applicationName = "clarity-connector-mail"
}

dependencies {
    implementation(projects.connectorSdk)
    implementation(libs.angus.mail)

    testImplementation(libs.greenmail)
}
