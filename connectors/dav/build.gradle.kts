plugins {
    alias(libs.plugins.clarity.jvm.app)
}

application {
    mainClass.set("net.robog.clarity.connector.dav.MainKt")
    applicationName = "clarity-connector-dav"
}

dependencies {
    implementation(projects.connectorSdk)
    implementation(libs.dav4jvm)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.auth)
    implementation(libs.ical4j)
    implementation(libs.ez.vcard)
    implementation(libs.pdfbox)
    implementation(libs.jsoup)

    testImplementation(testFixtures(projects.connectorSdk))
    testImplementation(libs.ktor.client.mock)
}
