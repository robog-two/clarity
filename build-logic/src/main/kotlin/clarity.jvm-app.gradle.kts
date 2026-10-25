// Runnable JVM service (backend, connectors). Packaged with `installDist` and shipped
// in a container by deploy/container/jvm-app.Containerfile.
plugins {
    id("clarity.kotlin-jvm")
    id("dev.zacsweers.metro")
    application
}

dependencies {
    "implementation"(catalog.lib("logback-classic"))
    "implementation"(catalog.lib("kotlin-logging"))
}

application {
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseZGC",
        "-XX:MaxRAMPercentage=75",
        "-XX:+ExitOnOutOfMemoryError",
    )
}
