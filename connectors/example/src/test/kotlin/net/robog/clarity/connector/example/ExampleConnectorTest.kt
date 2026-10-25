package net.robog.clarity.connector.example

import arrow.core.Either
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import net.robog.clarity.connector.sdk.ClientError
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.SyncScope
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.RecordResult

class ExampleConnectorTest : FunSpec({
    class FakeScope(override val config: ConnectorConfig) : SyncScope {
        override val state = InMemoryStateStore()
        override val log = KotlinLogging.logger("test")
        val pushed = mutableListOf<List<IngestRecord>>()
        override suspend fun pushCatching(records: List<IngestRecord>): Either<ClientError, List<RecordResult>> {
            pushed += records
            return emptyList<RecordResult>().right()
        }
    }

    fun config(values: Map<String, Boolean>) =
        ConnectorConfig(1, JsonObject(values.mapValues { JsonPrimitive(it.value) }))

    test("does nothing unless emitSample is enabled") {
        runTest {
            val scope = FakeScope(config(emptyMap()))
            scope.syncOnce(scope.config)
            scope.pushed shouldHaveSize 0
            scope.state.lastCursor() shouldBe null
        }
    }

    test("pushes the same deterministic record every run and stores a cursor") {
        runTest {
            val scope = FakeScope(config(mapOf("emitSample" to true)))
            repeat(2) { scope.syncOnce(scope.config) }
            scope.pushed shouldHaveSize 2
            scope.pushed[0] shouldBe scope.pushed[1]
            scope.pushed[0] shouldHaveSize 1
            scope.state.lastCursor() shouldBe SAMPLE_SOURCE_VERSION
        }
    }

    test("handles create_reminder by returning Done with a ref derived from the idempotency key") {
        val action = ProposedAction.CreateReminder("key-1", "remind", "call mum", Instant.parse("2026-03-04T12:00:00Z"))
        handleReminder(action) shouldBe ActionOutcome.Done(SourceId("example-reminder-key-1"))
    }

    test("the manifest's handles match the registered handlers") {
        manifest.handles shouldBe setOf(ActionKind.CREATE_REMINDER)
        actions.kinds shouldBe manifest.handles
    }

    test("manifest declares the message noun and the emitSample setting") {
        manifest.kind shouldBe "example"
        manifest.configSchema.containsKey("properties") shouldBe true
    }
})
