package net.robog.clarity.connector.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Paths
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConnectorSettingsTest : FunSpec({
    val noFiles: (String) -> String? = { null }

    test("defaults with key from the default secret file (trimmed)") {
        val s = ConnectorSettings.parse(emptyMap()) { if (it == "/run/secrets/connector_key") "abc\n" else null }.getOrNull()!!
        s.baseUrl shouldBe "http://backend:8080"
        s.connectorKey shouldBe "abc"
        s.syncInterval shouldBe 5.minutes
        s.stateDir shouldBe Paths.get("/data")
    }

    test("inline key wins over file; url trailing slash trimmed; custom dir") {
        val env = mapOf(
            "CLARITY_CONNECTOR_KEY" to "inline", "CLARITY_CONNECTOR_KEY_FILE" to "/x",
            "CLARITY_URL" to "http://localhost:8080/", "CLARITY_STATE_DIR" to "/var/state",
        )
        val s = ConnectorSettings.parse(env) { "file" }.getOrNull()!!
        s.connectorKey shouldBe "inline"
        s.baseUrl shouldBe "http://localhost:8080"
        s.stateDir shouldBe Paths.get("/var/state")
    }

    test("interval accepts ISO-8601 and Kotlin forms, rejects junk and non-positive") {
        fun interval(raw: String) = ConnectorSettings.parse(mapOf("CLARITY_CONNECTOR_KEY" to "k", "CLARITY_SYNC_INTERVAL" to raw), noFiles)
        interval("PT30S").getOrNull()!!.syncInterval shouldBe 30.seconds
        interval("2h").getOrNull()!!.syncInterval shouldBe 2.hours
        interval("1h 30m").getOrNull()!!.syncInterval shouldBe 90.minutes
        interval("soon").leftOrNull().shouldBeInstanceOf<SettingsError.InvalidInterval>()
        interval("0s").leftOrNull().shouldBeInstanceOf<SettingsError.InvalidInterval>()
    }

    test("missing and blank keys are typed errors") {
        ConnectorSettings.parse(emptyMap(), noFiles).leftOrNull().shouldBeInstanceOf<SettingsError.MissingKey>()
        ConnectorSettings.parse(emptyMap()) { "  \n" }.leftOrNull() shouldBe SettingsError.BlankKey
    }

    test("toString redacts the key") {
        testSettings.copy(connectorKey = "supersecret").toString() shouldNotContain "supersecret"
    }
})
