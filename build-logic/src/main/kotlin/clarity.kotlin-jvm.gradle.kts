// Plain Kotlin/JVM module (libraries such as connector-sdk).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val targetVersion = catalog.version("jvmTarget")

java {
    sourceCompatibility = JavaVersion.toVersion(targetVersion)
    targetCompatibility = JavaVersion.toVersion(targetVersion)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(targetVersion))
        allWarningsAsErrors.set(providers.gradleProperty("clarity.werror").map(String::toBoolean).orElse(false))
        freeCompilerArgs.addAll("-Xjsr305=strict")
        optIn.addAll("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    "testImplementation"(catalog.bundle("kotest"))
    "testImplementation"(catalog.lib("kotlinx-coroutines-test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
