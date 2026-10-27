package net.robog.clarity.backend.agent

import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

/** The audit trail in Postgres (V20), and decision calls recorded as `model_call` rows. */
@EnabledIf(ContainerRuntimeAvailable::class)
class BrowserAuditDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    fun row(seq: Int, before: ByteArray?, withheld: Boolean) = BrowserStepRecord(
        "run-1", seq, StepClock().now(), "act", "https://shop.example.com/", "Shop", "the Sign in link", "a form",
        before, withheld, buildJsonObject { put("top", 0.1) }, "link", "Sign in",
        buildJsonObject { put("ground", buildJsonObject { put("visible", 0.97) }) }, "passed", "click_by_uid {\"uid\":\"e1\"}",
        if (withheld) null else byteArrayOf(9, 8), withheld, "Page changed", 120,
    )

    test("steps are listed in order with which frames are stored; screenshots come back by id; withheld frames are null") {
        val audit = PgBrowserAudit(database)
        audit.record(row(1, byteArrayOf(1, 2, 3), withheld = false))
        audit.record(row(2, null, withheld = true))
        val steps = audit.steps("run-1")
        steps.map { it.seq } shouldBe listOf(1, 2)
        steps[0].beforeShot shouldBe true
        steps[0].afterShot shouldBe true
        steps[1].beforeShot shouldBe false
        steps[1].beforeWithheld shouldBe true
        steps[1].afterWithheld shouldBe true
        steps[0].jev shouldBe buildJsonObject { put("ground", buildJsonObject { put("visible", 0.97) }) }
        audit.screenshot(steps[0].id, after = false)!!.toList() shouldBe listOf<Byte>(1, 2, 3)
        audit.screenshot(steps[1].id, after = false) shouldBe null
        audit.steps("other") shouldBe emptyList()
    }

    test("a withheld frame can never be stored") {
        shouldThrow<IllegalArgumentException> { row(3, byteArrayOf(1), withheld = true) }
    }

    test("each Jev or Decision call through the recording client is a model_call row with its cost") {
        val inner = DecisionClient { _, _ -> DecisionResult(emptyMap(), "typesafe/jev-1.14", "TypeSafe", DecisionUsage(1200, 0, 50)).right() }
        RecordingDecisionClient(inner, "typesafe/jev-1.14-20261014", database, StepClock()).decide(JsonArray(emptyList()), emptyMap())
        val rows = database.tx { ModelCalls.selectAll().map { it[ModelCalls.model] to it[ModelCalls.costMicros] } }
        rows shouldBe listOf("typesafe/jev-1.14-20261014" to 50L)
    }
})
