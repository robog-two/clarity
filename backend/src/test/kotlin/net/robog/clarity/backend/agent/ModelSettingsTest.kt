package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.backend.governance.ConfiguredGuardModel
import net.robog.clarity.backend.governance.ConfiguredRuleDrafter
import net.robog.clarity.backend.governance.GuardQuestion
import net.robog.clarity.backend.governance.constitution
import net.robog.clarity.backend.governance.sample
import net.robog.clarity.backend.setup.modelKeyState
import net.robog.clarity.harness.COMPACTION_MIN_TRIGGER_TOKENS
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.SetupStatus

/** Runtime model settings: precedence, validation, and every consumer reading them per call. Never a network. */
class ModelSettingsTest : FunSpec({
    class Rig(anthropicKey: String? = "sk-ant", openRouterKey: String? = "sk-or", roles: AgentRoles = AgentRoles()) {
        val anthropic = FakeModel()
        val openRouter = FakeModel()
        val config = AgentConfig(anthropicApiKey = anthropicKey, reviewInterval = 2.hours, roles = roles, openRouterApiKey = openRouterKey)
        val store = InMemoryRoleOverrideStore()
        val settings = ModelSettings(config, store)
        val runtime = AgentRuntime(
            config, settings,
            ModelGatewayFactory { provider, _ -> if (provider == ModelProvider.OPENROUTER) openRouter else anthropic },
            UsageRecorder { _, _, _ -> },
        )
    }

    val keys = setOf(ModelProvider.ANTHROPIC, ModelProvider.OPENROUTER)
    fun question() = GuardQuestion(sample(ActionKind.SEND_EMAIL), constitution(), "because", GuardVerdict(Decision.ALLOW, emptyList(), "ok"))
    val guardAnswer = """{"decision":"allow","lines":[],"rationale":"fine"}"""

    test("an override wins field by field over the deploy-time setting, which wins over the built-in default") {
        // "Env": the helper's model set at deploy time; its effort and max tokens are built in.
        val boot = AgentRoles().let { it.copy(helper = it.helper.copy(model = "claude-opus-5-5")) }
        val rig = Rig(roles = boot)
        rig.settings[AgentRole.HELPER].model shouldBe "claude-opus-5-5"

        rig.settings.set(AgentRole.HELPER, RoleOverride(effort = "high"))
        rig.settings[AgentRole.HELPER] shouldBe boot.helper.copy(effort = "high")
        rig.settings.set(AgentRole.HELPER, RoleOverride(model = "claude-haiku-5-5"))
        // A PUT replaces the role's overrides: the effort override is gone.
        rig.settings[AgentRole.HELPER] shouldBe boot.helper.copy(model = "claude-haiku-5-5")
        rig.settings[AgentRole.HELPER].maxTokens shouldBe AgentRoles().helper.maxTokens
        rig.settings[AgentRole.LEAD] shouldBe boot.lead

        rig.settings.clear(AgentRole.HELPER)
        rig.settings[AgentRole.HELPER] shouldBe boot.helper
        rig.store.all() shouldBe emptyMap()
    }

    test("overrides saved earlier are in effect after a restart") {
        val rig = Rig()
        rig.store.put(AgentRole.GUARD, RoleOverride(model = "claude-sonnet-5-5"))
        rig.settings[AgentRole.GUARD].model shouldBe "claude-haiku-5-5"
        rig.settings.load()
        rig.settings[AgentRole.GUARD].model shouldBe "claude-sonnet-5-5"
        rig.settings.roles.value.guard.model shouldBe "claude-sonnet-5-5"
    }

    test("effectiveRoles leaves roles without an override alone") {
        val boot = AgentRoles()
        effectiveRoles(boot, emptyMap()) shouldBe boot
        effectiveRoles(boot, mapOf(AgentRole.TRIAGE to RoleOverride(cacheTtl = "1h"))).triage shouldBe boot.triage.copy(cacheTtl = "1h")
    }

    test("validation: any role (the lead too) may run on OpenRouter with its key, and each value must be one the API takes") {
        val boot = AgentRoles()
        fun errors(role: AgentRole, o: RoleOverride, k: Set<ModelProvider> = keys) = validateOverride(role, o, boot[role], k).map { it.field }

        errors(AgentRole.LEAD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "anthropic/claude-opus-5.5")).shouldBeEmpty()
        validateOverride(AgentRole.LEAD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "anthropic/claude-opus-5.5"), boot.lead, setOf(ModelProvider.ANTHROPIC))
            .single().message shouldContain "OPENROUTER_API_KEY"
        errors(AgentRole.GUARD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "anthropic/claude-haiku-5.5"), setOf(ModelProvider.ANTHROPIC)) shouldContainExactly listOf("provider")
        errors(AgentRole.GUARD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "anthropic/claude-haiku-5.5")).shouldBeEmpty()
        // Switching provider without a model in its naming is caught, both ways.
        errors(AgentRole.GUARD, RoleOverride(provider = ModelProvider.OPENROUTER)) shouldContainExactly listOf("model")
        errors(AgentRole.GUARD, RoleOverride(model = "anthropic/claude-haiku-5.5")) shouldContainExactly listOf("model")
        errors(AgentRole.GUARD, RoleOverride(model = " ")) shouldContainExactly listOf("model")
        // Changing the lead's Claude model is fine.
        errors(AgentRole.LEAD, RoleOverride(model = "claude-sonnet-5-5", effort = "high")).shouldBeEmpty()

        errors(AgentRole.HELPER, RoleOverride(effort = "extreme")) shouldContainExactly listOf("effort")
        errors(AgentRole.HELPER, RoleOverride(cacheTtl = "10m")) shouldContainExactly listOf("cacheTtl")
        errors(AgentRole.HELPER, RoleOverride(maxTokens = 0)) shouldContainExactly listOf("maxTokens")
        errors(AgentRole.LEAD, RoleOverride(compactAtTokens = COMPACTION_MIN_TRIGGER_TOKENS - 1)) shouldContainExactly listOf("compactAtTokens")
        errors(AgentRole.LEAD, RoleOverride(compactAtTokens = COMPACTION_MIN_TRIGGER_TOKENS)).shouldBeEmpty()
        // Only what the override sets is checked: an odd deploy-time effort does not block other changes.
        validateOverride(AgentRole.HELPER, RoleOverride(cacheTtl = "1h"), RoleConfig("claude-sonnet-5-5", effort = "odd"), keys).shouldBeEmpty()
    }

    test("the guard picks up a new model and provider on its next check, without a rebuild") {
        val rig = Rig()
        rig.anthropic.handler = { end(guardAnswer) }
        rig.openRouter.handler = { end(guardAnswer) }
        val guard = ConfiguredGuardModel(rig.runtime)
        guard.review(question())
        rig.anthropic.requests.single().model shouldBe "claude-haiku-5-5"
        rig.anthropic.requests.single().effort shouldBe "low"

        rig.settings.set(AgentRole.GUARD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "google/gemini-flash", effort = "medium"))
        guard.review(question())
        rig.openRouter.requests.single().model shouldBe "google/gemini-flash"
        rig.openRouter.requests.single().effort shouldBe "medium"
        rig.anthropic.requests.size shouldBe 1
    }

    test("the rule drafter follows the guard role's model on its next draft") {
        val rig = Rig()
        rig.anthropic.handler = { end("""{"text":"Book my haircuts, then tell me","because":"routine"}""") }
        val drafter = ConfiguredRuleDrafter(rig.runtime)
        val held = HeldAction(
            HeldActionId("h1"), kotlin.time.Clock.System.now(), sample(ActionKind.SEND_EMAIL), GuardVerdict(Decision.ASK, emptyList(), "asking"),
            "x", emptyList(), net.robog.clarity.shared.governance.Urgency.NORMAL, HeldAction.Status.HELD,
        )
        drafter.draft(held, null)
        rig.settings.set(AgentRole.GUARD, RoleOverride(model = "claude-sonnet-5-5"))
        drafter.draft(held, null)
        rig.anthropic.requests.map { it.model } shouldContainExactly listOf("claude-haiku-5-5", "claude-sonnet-5-5")
    }

    test("triage picks up a new model on its next step") {
        val rig = Rig()
        rig.anthropic.handler = { end("""{"verdict":"ok","reason":"fine"}""") }
        rig.openRouter.handler = { end("""{"verdict":"ok","reason":"fine"}""") }
        val triage = ConfiguredBrowserTriage(rig.runtime)
        val step = TriageStep("click Next", "https://example.com", untrusted = false)
        triage.review(step).ok shouldBe true
        rig.settings.set(AgentRole.TRIAGE, RoleOverride(provider = ModelProvider.OPENROUTER, model = "openai/gpt-mini"))
        triage.review(step).ok shouldBe true
        rig.anthropic.requests.single().model shouldBe "claude-haiku-5-5"
        rig.openRouter.requests.single().model shouldBe "openai/gpt-mini"
    }

    test("agents are enabled when every provider some role uses now has a key, and wait for a role to move to one that has") {
        val rig = Rig(openRouterKey = null, roles = AgentRoles().let { it.copy(guard = it.guard.copy(provider = ModelProvider.OPENROUTER, model = "x/y")) })
        rig.runtime.missingKeys() shouldBe setOf(ModelProvider.OPENROUTER)
        rig.runtime.enabled shouldBe false
        coroutineScope {
            val waiting = async { rig.runtime.awaitEnabled() }
            yield()
            waiting.isCompleted shouldBe false
            rig.settings.set(AgentRole.GUARD, RoleOverride(provider = ModelProvider.ANTHROPIC, model = "claude-haiku-5-5"))
            withTimeout(5.seconds) { waiting.await() }
        }
        rig.runtime.enabled shouldBe true
        rig.runtime.providersWithKeys shouldBe setOf(ModelProvider.ANTHROPIC)
    }

    test("setup status: valid only when every provider in use has a key") {
        modelKeyState(emptySet(), refused = false) shouldBe SetupStatus.KeyState.VALID
        modelKeyState(setOf(ModelProvider.ANTHROPIC), refused = false) shouldBe SetupStatus.KeyState.MISSING
        modelKeyState(setOf(ModelProvider.ANTHROPIC), refused = true) shouldBe SetupStatus.KeyState.INVALID
        // The Anthropic key is fine but a role runs on OpenRouter without its key: not valid, and not the app's key to blame.
        modelKeyState(setOf(ModelProvider.OPENROUTER), refused = true) shouldBe SetupStatus.KeyState.MISSING
    }

    test("a helper run keeps the settings it was bound with") {
        val rig = Rig()
        val bound = rig.runtime.bindOrFail(AgentRole.HELPER)
        rig.settings.set(AgentRole.HELPER, RoleOverride(model = "claude-haiku-5-5"))
        bound.config.model shouldBe "claude-sonnet-5-5"
        rig.runtime.bindOrFail(AgentRole.HELPER).config.model shouldBe "claude-haiku-5-5"
    }
})
