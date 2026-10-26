package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.ModelProvider
import net.robog.clarity.backend.governance.ConfiguredGuardModel
import net.robog.clarity.backend.governance.GuardQuestion
import net.robog.clarity.backend.governance.constitution
import net.robog.clarity.backend.governance.sample
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict

/** Each role gets the gateway of its own provider; never a network. */
class ProviderRoutingTest : FunSpec({
    val openRouterRoles = AgentRoles().let {
        it.copy(
            guard = it.guard.copy(provider = ModelProvider.OPENROUTER, model = "anthropic/claude-haiku-5.5"),
            triage = it.triage.copy(provider = ModelProvider.OPENROUTER, model = "google/gemini-flash"),
        )
    }

    class Rig(anthropicKey: String?, openRouterKey: String?, roles: AgentRoles) {
        val anthropic = FakeModel()
        val openRouter = FakeModel()
        val created = mutableListOf<Pair<ModelProvider, String>>()
        val recorded = mutableListOf<Pair<String, Usage>>()
        val config = AgentConfig(anthropicApiKey = anthropicKey, reviewInterval = 2.hours, roles = roles, openRouterApiKey = openRouterKey)
        val runtime = AgentRuntime(
            config,
            ModelGatewayFactory { provider, key ->
                created += provider to key
                if (provider == ModelProvider.OPENROUTER) openRouter else anthropic
            },
            UsageRecorder { model, usage -> recorded += model to usage },
        )
    }

    test("guard and triage on OpenRouter use its gateway and their own models; the lead and helpers stay on Anthropic") {
        val rig = Rig("sk-ant", "sk-or", openRouterRoles)
        rig.created shouldContainExactly listOf(ModelProvider.ANTHROPIC to "sk-ant", ModelProvider.OPENROUTER to "sk-or")
        rig.runtime.enabled shouldBe true

        rig.openRouter.handler = {
            ModelResponse(listOf(Block.Text("""{"decision":"allow","lines":[],"rationale":"fine"}""")), StopReason.END_TURN, Usage(50, 5, reportedCostMicros = 12))
        }
        val verdict = ConfiguredGuardModel(rig.runtime, rig.config).review(
            GuardQuestion(sample(ActionKind.SEND_EMAIL), constitution(), "because", GuardVerdict(Decision.ALLOW, emptyList(), "ok")),
        )
        verdict.decision shouldBe Decision.ALLOW
        rig.openRouter.requests.single().model shouldBe "anthropic/claude-haiku-5.5"
        rig.anthropic.requests shouldBe emptyList()
        // Recorded with the provider's own cost.
        rig.recorded.single() shouldBe ("anthropic/claude-haiku-5.5" to Usage(50, 5, reportedCostMicros = 12))
        costMicros(rig.recorded.single().first, rig.recorded.single().second) shouldBe 12L

        rig.openRouter.handler = { end("""{"verdict":"ok","reason":"fine"}""") }
        ConfiguredBrowserTriage(rig.runtime).review(TriageStep("click Next", "https://example.com", untrusted = false)).ok shouldBe true
        rig.openRouter.requests.last().model shouldBe "google/gemini-flash"

        rig.runtime.gatewayFor(AgentRole.LEAD).also { lead ->
            lead!!.generate(net.robog.clarity.harness.ModelRequest(LEAD_MODEL, "s", listOf(net.robog.clarity.harness.Message.user("hi")), emptyList(), 10, null))
        }
        rig.anthropic.requests.single().model shouldBe LEAD_MODEL
        rig.runtime.gatewayFor(AgentRole.HELPER) shouldBe rig.runtime.gatewayFor(AgentRole.LEAD)
        (rig.runtime.gatewayFor(AgentRole.TRIAGE) === rig.runtime.gatewayFor(AgentRole.GUARD)) shouldBe true
    }

    test("agents wait for every provider in use: an Anthropic key entered in setup completes the set") {
        val rig = Rig(null, "sk-or", openRouterRoles)
        rig.runtime.enabled shouldBe false
        (rig.runtime.gatewayFor(AgentRole.GUARD) != null) shouldBe true
        rig.runtime.gatewayFor(AgentRole.LEAD) shouldBe null
        coroutineScope {
            val waiting = async { rig.runtime.awaitEnabled() }
            yield()
            waiting.isCompleted shouldBe false
            rig.runtime.installKey("sk-ant-setup") shouldBe true
            withTimeout(5.seconds) { waiting.await() }
        }
        rig.runtime.enabled shouldBe true
        rig.created.last() shouldBe (ModelProvider.ANTHROPIC to "sk-ant-setup")
    }

    test("an OpenRouter key alone enables agents only when no role needs Anthropic; a configured Anthropic key still wins") {
        val all = AgentRoles().let { r ->
            AgentRoles(
                lead = r.lead.copy(provider = ModelProvider.OPENROUTER),
                helper = r.helper.copy(provider = ModelProvider.OPENROUTER),
                guard = r.guard.copy(provider = ModelProvider.OPENROUTER),
                triage = r.triage.copy(provider = ModelProvider.OPENROUTER),
            )
        }
        Rig(null, "sk-or", all).runtime.enabled shouldBe true
        Rig(null, "sk-or", AgentRoles()).runtime.enabled shouldBe false
        // Defaults: everything on Anthropic, the OpenRouter key unused but harmless.
        val configured = Rig("sk-ant", null, AgentRoles())
        configured.runtime.enabled shouldBe true
        configured.runtime.installKey("other") shouldBe false
        configured.created shouldContainExactly listOf(ModelProvider.ANTHROPIC to "sk-ant")
    }
})
