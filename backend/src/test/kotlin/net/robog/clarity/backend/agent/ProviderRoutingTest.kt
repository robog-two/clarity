package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
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

/** One OpenRouter gateway for every role, each with its own model, every call recorded as its role; never a network. */
class ProviderRoutingTest : FunSpec({
    val roles = AgentRoles().let {
        it.copy(
            guard = it.guard.copy(model = "anthropic/claude-haiku-5.5"),
            triage = it.triage.copy(model = "google/gemini-flash"),
        )
    }

    class Rig(openRouterKey: String?, roles: AgentRoles) {
        val model = FakeModel()
        val created = mutableListOf<String>()
        val recorded = mutableListOf<Pair<String, Usage>>()
        val recordedRoles = mutableListOf<AgentRole?>()
        val config = AgentConfig(openRouterApiKey = openRouterKey, reviewInterval = 2.hours, roles = roles)
        val settings = ModelSettings(config, InMemoryRoleOverrideStore())
        val runtime = AgentRuntime(
            config,
            settings,
            ModelGatewayFactory { key ->
                created += key
                model
            },
            UsageRecorder { model, usage, role ->
                recorded += model to usage
                recordedRoles += role
            },
        )
    }

    test("the gateway is built once from the key; guard and triage call it with their own models, recorded with OpenRouter's cost") {
        val rig = Rig("sk-or", roles)
        rig.created shouldContainExactly listOf("sk-or")
        rig.runtime.enabled shouldBe true

        rig.model.handler = {
            ModelResponse(listOf(Block.Text("""{"decision":"allow","lines":[],"rationale":"fine"}""")), StopReason.END_TURN, Usage(50, 5, reportedCostMicros = 12))
        }
        val verdict = ConfiguredGuardModel(rig.runtime).review(
            GuardQuestion(sample(ActionKind.SEND_EMAIL), constitution(), "because", GuardVerdict(Decision.ALLOW, emptyList(), "ok")),
        )
        verdict.decision shouldBe Decision.ALLOW
        rig.model.requests.single().model shouldBe "anthropic/claude-haiku-5.5"
        rig.recorded.single() shouldBe ("anthropic/claude-haiku-5.5" to Usage(50, 5, reportedCostMicros = 12))
        costMicros(rig.recorded.single().second) shouldBe 12L
        rig.recordedRoles.single() shouldBe AgentRole.GUARD

        rig.model.handler = { end("""{"verdict":"ok","reason":"fine"}""") }
        ConfiguredBrowserTriage(rig.runtime).review(TriageStep("click Next", "https://example.com", untrusted = false)).ok shouldBe true
        rig.model.requests.last().model shouldBe "google/gemini-flash"
        rig.recordedRoles.last() shouldBe AgentRole.TRIAGE

        rig.runtime.gatewayFor(AgentRole.LEAD)!!
            .generate(net.robog.clarity.harness.ModelRequest(roles.lead.model, "s", listOf(net.robog.clarity.harness.Message.user("hi")), emptyList(), 10, null))
        rig.model.requests.last().model shouldBe "xiaomi/mimo-v2.6-pro"
        rig.recordedRoles.last() shouldBe AgentRole.LEAD
        rig.created.size shouldBe 1
    }

    test("without a key nothing is built and no role can run") {
        val rig = Rig(null, roles)
        rig.created shouldBe emptyList()
        rig.runtime.enabled shouldBe false
        AgentRole.entries.forEach { rig.runtime.gatewayFor(it) shouldBe null }
        Rig(" ", roles).runtime.enabled shouldBe false
    }
})
