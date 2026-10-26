package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage

class BrowserTriageTest : FunSpec({
    fun answering(text: String) = object : ModelGateway {
        override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit) =
            ModelResponse(listOf(Block.Text(text)), StopReason.MAX_TOKENS, Usage())
    }
    val step = TriageStep("Click button \"SMS\" on www.ulta.com/verify", "https://www.ulta.com/verify", untrusted = true)

    test("a cut-off answer asks the person instead of failing the step") {
        val verdict = GatewayBrowserTriage({ answering("""{"verdict":"ask","reason":"Clicking SMS on Ulta's verification page would send""") }, "m").review(step)
        verdict.ok shouldBe false
    }

    test("a whole answer is used") {
        GatewayBrowserTriage({ answering("""{"verdict":"ok","reason":"just browsing"}""") }, "m").review(step) shouldBe TriageVerdict(true, "just browsing")
    }
})
