package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ModelsRepository
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.settings.ModelsViewModel
import net.robog.clarity.app.ui.settings.changedOverride
import net.robog.clarity.app.ui.settings.MODEL_SUGGESTIONS
import net.robog.clarity.app.ui.settings.modelRows
import net.robog.clarity.app.ui.settings.overrideOf
import net.robog.clarity.app.ui.settings.refusal
import net.robog.clarity.shared.api.FieldError
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride
import net.robog.clarity.shared.session.RoleModelSettings
import net.robog.clarity.shared.session.RoleModelView
import net.robog.clarity.shared.session.SettingSource

private val FIELDS = listOf("provider", "model", "effort", "maxTokens", "cacheTtl", "compaction", "compactAtTokens")

private fun settings(model: String = "deepseek/deepseek-v4.1-flash-1017", effort: String = "low") =
    RoleModelSettings(model, effort, 4_000, "5m", false, 150_000)

/** A role as the server shows it after [override]. */
private fun roleView(name: String, default: RoleModelSettings, override: RoleModelOverride = RoleModelOverride()) = RoleModelView(
    role = name,
    effective = default.copy(
        model = override.model ?: default.model,
        effort = override.effort ?: default.effort,
    ),
    default = default,
    sources = FIELDS.associateWith { f ->
        val set = when (f) { "model" -> override.model; "effort" -> override.effort; else -> null }
        if (set != null) SettingSource.OVERRIDE else SettingSource.DEFAULT
    },
)

private fun view(overrides: Map<String, RoleModelOverride> = emptyMap()) = ModelSettingsView(
    roles = listOf(
        roleView("lead", settings(model = "xiaomi/mimo-v2.6-pro", effort = "medium"), overrides["lead"] ?: RoleModelOverride()),
        roleView("guard", settings(), overrides["guard"] ?: RoleModelOverride()),
    ),
)

/** A server that keeps overrides like the real one and can refuse. */
private class FakeModels(var refuse: ApiError? = null, var missing: Boolean = false) : ModelsRepository {
    val saved = mutableListOf<Pair<String, RoleModelOverride>>()
    val resets = mutableListOf<String>()
    var overrides = mutableMapOf<String, RoleModelOverride>()

    override suspend fun load(): ApiResult<ModelSettingsView> =
        if (missing) ApiResult.Failure(ApiError.NotFound(null)) else ApiResult.Success(view(overrides))

    override suspend fun save(role: String, override: RoleModelOverride): ApiResult<ModelSettingsView> {
        saved += role to override
        refuse?.let { return ApiResult.Failure(it) }
        overrides[role] = override
        return ApiResult.Success(view(overrides))
    }

    override suspend fun reset(role: String): ApiResult<ModelSettingsView> {
        resets += role
        overrides.remove(role)
        return ApiResult.Success(view(overrides))
    }
}

class ModelsRulesTest {
    @Test fun suggestionsAreOpenRouterIds() {
        assertTrue(MODEL_SUGGESTIONS.all { '/' in it })
        assertTrue("anthropic/claude-opus-5.5" in MODEL_SUGGESTIONS)
        assertEquals(MODEL_SUGGESTIONS, modelRows(view()).first().suggestions)
    }

    @Test fun aChangeKeepsOtherOverridesAndDropsValuesEqualToTheDefault() {
        val guard = roleView("guard", settings(), RoleModelOverride(effort = "medium"))
        assertEquals(RoleModelOverride(effort = "medium"), overrideOf(guard))
        assertEquals(
            RoleModelOverride(model = "anthropic/claude-haiku-5.5", effort = "medium"),
            changedOverride(guard, model = "anthropic/claude-haiku-5.5"),
        )
        // Picking the default again unsets it.
        assertEquals(RoleModelOverride(), changedOverride(guard, effort = "low"))
    }

    @Test fun aProviderFromAnOlderServerIsNeverSentBack() {
        // An older server marks the provider as set in the app; it is not an override any more.
        val old = roleView("guard", settings(), RoleModelOverride(model = "x/y")).let { v ->
            v.copy(sources = v.sources + ("provider" to SettingSource.OVERRIDE))
        }
        assertNull(overrideOf(old).provider)
        assertNull(changedOverride(old, effort = "high").provider)
        // A role whose only "override" is that provider still reads as the default.
        val providerOnly = roleView("guard", settings()).let { v -> v.copy(sources = v.sources + ("provider" to SettingSource.OVERRIDE)) }
        assertTrue(modelRows(ModelSettingsView(listOf(providerOnly))).single().isDefault)
    }

    @Test fun theLeadSaysWhenChangesApply() {
        val lead = modelRows(view()).first { it.role == "lead" }
        assertEquals("Changes apply from its next turn. It summarizes its own thread when the thread gets long.", lead.note)
        assertTrue(lead.isDefault)
    }

    @Test fun aRefusalReadsTheServersFieldReasons() {
        val problem = Problem(title = "Settings not saved", status = 422, errors = listOf(FieldError("effort", "effort is one of low, medium")))
        assertEquals("Effort is one of low, medium.", refusal(ApiError.Http(422, problem)))
        assertEquals("Can’t reach your server right now.", refusal(ApiError.Unreachable(null)))
    }
}

class ModelsViewModelTest {
    @Test fun changesAreSavedAndShown() = withMain {
        val server = FakeModels()
        val vm = ModelsViewModel(server)
        assertEquals(listOf("lead", "guard"), vm.state.value.rows.map { it.role })

        vm.setModel("guard", "anthropic/claude-haiku-5.5")
        assertEquals("guard" to RoleModelOverride(model = "anthropic/claude-haiku-5.5"), server.saved.last())
        val guard = vm.state.value.rows.first { it.role == "guard" }
        assertEquals("anthropic/claude-haiku-5.5", guard.model)
        assertFalse(guard.isDefault)

        vm.setEffort("lead", "high")
        vm.setModel("lead", " anthropic/claude-sonnet-5.5 ")
        assertEquals("lead" to RoleModelOverride(model = "anthropic/claude-sonnet-5.5", effort = "high"), server.saved.last())
        assertEquals("anthropic/claude-sonnet-5.5", vm.state.value.rows.first { it.role == "lead" }.model)

        // Unchanged values are not sent again.
        val before = server.saved.size
        vm.setModel("lead", "anthropic/claude-sonnet-5.5")
        assertEquals(before, server.saved.size)

        vm.reset("guard")
        assertEquals(listOf("guard"), server.resets)
        assertTrue(vm.state.value.rows.first { it.role == "guard" }.isDefault)
    }

    @Test fun aRefusedChangeGoesBackAndSaysWhy() = withMain {
        val problem = Problem(title = "Settings not saved", status = 422, errors = listOf(FieldError("model", "OpenRouter model names look like vendor/model")))
        val server = FakeModels(refuse = ApiError.Http(422, problem))
        val vm = ModelsViewModel(server)
        vm.setModel("guard", "gemini")
        val guard = vm.state.value.rows.first { it.role == "guard" }
        assertEquals("deepseek/deepseek-v4.1-flash-1017", guard.model)
        assertEquals("OpenRouter model names look like vendor/model.", guard.error)
        // The next successful change clears it.
        server.refuse = null
        vm.setEffort("guard", "medium")
        assertNull(vm.state.value.rows.first { it.role == "guard" }.error)
    }

    @Test fun anOlderServerHidesTheSection() = withMain {
        val vm = ModelsViewModel(FakeModels(missing = true))
        assertFalse(vm.state.value.available)
        assertNull(vm.state.value.message)
    }
}
