package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage

class ModelCostsTest : FunSpec({
    test("an unsplit cache write counts as 5 minute writes") {
        cacheWrites(Usage(cacheCreationInputTokens = 500)) shouldBe (500L to 0L)
        cacheWrites(Usage(cacheCreationInputTokens = 500, cacheCreation1hTokens = 200)) shouldBe (300L to 200L)
    }

    test("the cost is what OpenRouter reports (cache reads and writes included); without a report there is none") {
        costMicros(Usage(10_000, 1_000, reportedCostMicros = 1_234)) shouldBe 1_234L
        costMicros(Usage(1_000_000, 0, cacheCreationInputTokens = 5_000, cacheReadInputTokens = 90_000, reportedCostMicros = 7)) shouldBe 7L
        costMicros(Usage(10_000, 1_000)) shouldBe null
        (Usage(1, 1, reportedCostMicros = 5) + Usage(1, 1)).reportedCostMicros shouldBe 5L
        (Usage(1, 1) + Usage(1, 1)).reportedCostMicros shouldBe null
    }

    test("every call through the gateway is recorded, and a failing recorder never fails the call") {
        val response = ModelResponse(listOf(Block.Text("hi")), StopReason.END_TURN, Usage(10, 5))
        val inner = object : ModelGateway {
            override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit) = response
        }
        val seen = mutableListOf<Pair<String, Usage>>()
        val request = ModelRequest("anthropic/claude-opus-5.5", emptyList(), emptyList(), emptyList(), 10, null)
        RecordingGateway(inner, { m, u, _ -> seen += m to u }).generate(request) shouldBe response
        RecordingGateway(inner, { m, u, _ -> seen += m to u }).complete(request) shouldBe response
        seen shouldBe listOf("anthropic/claude-opus-5.5" to Usage(10, 5), "anthropic/claude-opus-5.5" to Usage(10, 5))
        RecordingGateway(inner, { _, _, _ -> error("db down") }).generate(request) shouldBe response
    }
})
