package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.DEFAULT_HELPER_MODEL
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.backend.governance.ConfiguredGuardModel
import net.robog.clarity.backend.governance.ConfiguredRuleDrafter
import net.robog.clarity.backend.governance.GuardQuestion
import net.robog.clarity.backend.governance.constitution
import net.robog.clarity.backend.governance.sample
import net.robog.clarity.backend.setup.modelKeyState
import net.robog.clarity.harness.MIN_COMPACT_AT_TOKENS
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.SetupStatus

/** Runtime model settings: precedence, validation, and every consumer reading them per call. Never a network. */
class ModelSettingsTest : FunSpec({
    class Rig(openRouterKey: String? = "sk-or", roles: AgentRoles = AgentRoles()) {
        val model = FakeModel()
        val config = AgentConfig(openRouterApiKey = openRouterKey, reviewInterval = 2.hours, roles = roles)
        val store = InMemoryRoleOverrideStore()
        val settings = ModelSettings(config, store)
        val runtime = AgentRuntime(config, settings, ModelGatewayFactory { model }, UsageRecorder { _, _, _ -> })
    }

    fun question() = GuardQuestion(sample(ActionKind.SEND_EMAIL), constitution(), "because", GuardVerdict(Decision.ALLOW, emptyList(), "ok"))
    val guardAnswer = """{"decision":"allow","lines":[],"rationale":"fine"}"""

    test("the built-in defaults: every role on an OpenRouter model") {
        val roles = AgentRoles()
        roles.lead.model shouldBe "xiaomi/mimo-v2.6-pro"
        listOf(roles.helper, roles.guard, roles.triage).map { it.model }.toSet() shouldBe setOf("deepseek/deepseek-v4.1-flash-1017")
        roles.lead.compaction shouldBe true
    }

    test("an override wins field by field over the deploy-time setting, which wins over the built-in default") {
        // "Env": the helper's model set at deploy time; its effort and max tokens are built in.
        val boot = AgentRoles().let { it.copy(helper = it.helper.copy(model = "anthropic/claude-opus-5.5")) }
        val rig = Rig(roles = boot)
        rig.settings[AgentRole.HELPER].model shouldBe "anthropic/claude-opus-5.5"

        rig.settings.set(AgentRole.HELPER, RoleOverride(effort = "high"))
        rig.settings[AgentRole.HELPER] shouldBe boot.helper.copy(effort = "high")
        rig.settings.set(AgentRole.HELPER, RoleOverride(model = "anthropic/claude-haiku-5.5"))
        // A PUT replaces the role's overrides: the effort override is gone.
        rig.settings[AgentRole.HELPER] shouldBe boot.helper.copy(model = "anthropic/claude-haiku-5.5")
        rig.settings[AgentRole.HELPER].maxTokens shouldBe AgentRoles().helper.maxTokens
        rig.settings[AgentRole.LEAD] shouldBe boot.lead

        rig.settings.clear(AgentRole.HELPER)
        rig.settings[AgentRole.HELPER] shouldBe boot.helper
        rig.store.all() shouldBe emptyMap()
    }

    test("overrides saved earlier are in effect after a restart") {
        val rig = Rig()
        rig.store.put(AgentRole.GUARD, RoleOverride(model = "anthropic/claude-sonnet-5.5"))
        rig.settings[AgentRole.GUARD].model shouldBe DEFAULT_HELPER_MODEL
        rig.settings.load()
        rig.settings[AgentRole.GUARD].model shouldBe "anthropic/claude-sonnet-5.5"
        rig.settings.roles.value.guard.model shouldBe "anthropic/claude-sonnet-5.5"
    }

    test("a stored model that is not an OpenRouter id (saved for the old Anthropic API) falls back to the default") {
        val boot = AgentRoles()
        effectiveRoles(boot, mapOf(AgentRole.GUARD to RoleOverride(model = "claude-haiku-5-5", effort = "high"))).guard shouldBe
            boot.guard.copy(effort = "high")
    }

    test("effectiveRoles leaves roles without an override alone") {
        val boot = AgentRoles()
        effectiveRoles(boot, emptyMap()) shouldBe boot
        effectiveRoles(boot, mapOf(AgentRole.TRIAGE to RoleOverride(cacheTtl = "1h"))).triage shouldBe boot.triage.copy(cacheTtl = "1h")
    }

    test("validation: models are OpenRouter ids, and each value must be one the API takes") {
        fun errors(o: RoleOverride) = validateOverride(o).map { it.field }

        errors(RoleOverride(model = "anthropic/claude-opus-5.5")).shouldBeEmpty()
        errors(RoleOverride(model = "deepseek/deepseek-v4.1-flash-1017", effort = "high")).shouldBeEmpty()
        // A model without a vendor (an old Anthropic API name) is refused.
        errors(RoleOverride(model = "claude-haiku-5-5")) shouldContainExactly listOf("model")
        errors(RoleOverride(model = " ")) shouldContainExactly listOf("model")
        errors(RoleOverride(model = "a/b c")) shouldContainExactly listOf("model")

        errors(RoleOverride(effort = "extreme")) shouldContainExactly listOf("effort")
        errors(RoleOverride(cacheTtl = "10m")) shouldContainExactly listOf("cacheTtl")
        errors(RoleOverride(maxTokens = 0)) shouldContainExactly listOf("maxTokens")
        errors(RoleOverride(compactAtTokens = MIN_COMPACT_AT_TOKENS - 1)) shouldContainExactly listOf("compactAtTokens")
        errors(RoleOverride(compactAtTokens = MIN_COMPACT_AT_TOKENS)).shouldBeEmpty()
        // Only what the override sets is checked: an odd deploy-time effort does not block other changes.
        validateOverride(RoleOverride(cacheTtl = "1h")).shouldBeEmpty()
        RoleOverride(cacheTtl = "1h").applyTo(RoleConfig("a/b", effort = "odd")).effort shouldBe "odd"
    }

    test("the guard picks up a new model on its next check, without a rebuild") {
        val rig = Rig()
        rig.model.handler = { end(guardAnswer) }
        val guard = ConfiguredGuardModel(rig.runtime)
        guard.review(question())
        rig.model.requests.single().model shouldBe DEFAULT_HELPER_MODEL
        rig.model.requests.single().effort shouldBe "low"

        rig.settings.set(AgentRole.GUARD, RoleOverride(model = "google/gemini-flash", effort = "medium"))
        guard.review(question())
        rig.model.requests.last().model shouldBe "google/gemini-flash"
        rig.model.requests.last().effort shouldBe "medium"
    }

    test("the rule drafter follows the guard role's model on its next draft") {
        val rig = Rig()
        rig.model.handler = { end("""{"text":"Book my haircuts, then tell me","because":"routine"}""") }
        val drafter = ConfiguredRuleDrafter(rig.runtime)
        val held = HeldAction(
            HeldActionId("h1"), kotlin.time.Clock.System.now(), sample(ActionKind.SEND_EMAIL), GuardVerdict(Decision.ASK, emptyList(), "asking"),
            "x", emptyList(), net.robog.clarity.shared.governance.Urgency.NORMAL, HeldAction.Status.HELD,
        )
        drafter.draft(held, null)
        rig.settings.set(AgentRole.GUARD, RoleOverride(model = "anthropic/claude-sonnet-5.5"))
        drafter.draft(held, null)
        rig.model.requests.map { it.model } shouldContainExactly listOf(DEFAULT_HELPER_MODEL, "anthropic/claude-sonnet-5.5")
    }

    test("triage picks up a new model on its next step") {
        val rig = Rig()
        rig.model.handler = { end("""{"verdict":"ok","reason":"fine"}""") }
        val triage = ConfiguredBrowserTriage(rig.runtime)
        val step = TriageStep("click Next", "https://example.com", untrusted = false)
        triage.review(step).ok shouldBe true
        rig.settings.set(AgentRole.TRIAGE, RoleOverride(model = "openai/gpt-mini"))
        triage.review(step).ok shouldBe true
        rig.model.requests.map { it.model } shouldContainExactly listOf(DEFAULT_HELPER_MODEL, "openai/gpt-mini")
    }

    test("without the OpenRouter key agents are disabled and the key is reported missing") {
        val rig = Rig(openRouterKey = null)
        rig.runtime.missingKeys shouldBe setOf("openrouter")
        rig.runtime.enabled shouldBe false
        rig.runtime.bind(AgentRole.GUARD) shouldBe null
        val with = Rig()
        with.runtime.missingKeys shouldBe emptySet()
        with.runtime.enabled shouldBe true
    }

    test("setup status: valid only when no key is missing") {
        modelKeyState(emptySet()) shouldBe SetupStatus.KeyState.VALID
        modelKeyState(setOf("openrouter")) shouldBe SetupStatus.KeyState.MISSING
    }

    test("a helper run keeps the settings it was bound with") {
        val rig = Rig()
        val bound = rig.runtime.bindOrFail(AgentRole.HELPER)
        rig.settings.set(AgentRole.HELPER, RoleOverride(model = "anthropic/claude-haiku-5.5"))
        bound.config.model shouldBe DEFAULT_HELPER_MODEL
        rig.runtime.bindOrFail(AgentRole.HELPER).config.model shouldBe "anthropic/claude-haiku-5.5"
    }
})
