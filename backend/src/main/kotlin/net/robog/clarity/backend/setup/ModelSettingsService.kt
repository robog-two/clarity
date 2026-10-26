package net.robog.clarity.backend.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import net.robog.clarity.backend.agent.AgentRuntime
import net.robog.clarity.backend.agent.RoleOverride
import net.robog.clarity.backend.agent.SettingError
import net.robog.clarity.backend.agent.StoredOverride
import net.robog.clarity.backend.agent.agentRoleOf
import net.robog.clarity.backend.agent.modelProviderOf
import net.robog.clarity.backend.agent.validateOverride
import net.robog.clarity.backend.agent.wireName
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.api.FieldError
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride
import net.robog.clarity.shared.session.RoleModelSettings
import net.robog.clarity.shared.session.RoleModelView
import net.robog.clarity.shared.session.SettingSource

private val log = KotlinLogging.logger {}

/** Why a model settings change was not saved. */
sealed interface ModelSettingsError : HttpProblem {
    data class UnknownRole(val role: String) : ModelSettingsError {
        override val status = HttpStatusCode.NotFound
        override val title = "Not found"
        override val detail = "no role '$role' (lead, helper, guard or triage)"
    }

    data class Invalid(override val errors: List<FieldError>) : ModelSettingsError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "Settings not saved"
        override val detail = errors.joinToString("; ") { it.message }
    }
}

fun RoleConfig.toWire(): RoleModelSettings =
    RoleModelSettings(provider.wireName, model, effort, maxTokens, cacheTtl, compaction, compactAtTokens)

/** Pure: the override the app sent, with the provider named, or what cannot be read. Blank fields count as unset. */
fun overrideFromWire(body: RoleModelOverride): Either<List<SettingError>, RoleOverride> {
    val rawProvider = body.provider?.takeIf(String::isNotBlank)
    val provider = rawProvider?.let(::modelProviderOf)
    if (rawProvider != null && provider == null) {
        return listOf(SettingError("provider", "unknown provider '$rawProvider' (anthropic or openrouter)")).left()
    }
    return RoleOverride(
        provider = provider,
        model = body.model?.trim(),
        effort = body.effort?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
        maxTokens = body.maxTokens,
        cacheTtl = body.cacheTtl?.trim()?.takeIf(String::isNotEmpty),
        compaction = body.compaction,
        compactAtTokens = body.compactAtTokens,
    ).right()
}

/** Pure: where each field in effect comes from. */
fun sourcesOf(override: RoleOverride?): Map<String, SettingSource> {
    fun of(set: Any?) = if (set != null) SettingSource.OVERRIDE else SettingSource.DEFAULT
    return linkedMapOf(
        "provider" to of(override?.provider),
        "model" to of(override?.model),
        "effort" to of(override?.effort),
        "maxTokens" to of(override?.maxTokens),
        "cacheTtl" to of(override?.cacheTtl),
        "compaction" to of(override?.compaction),
        "compactAtTokens" to of(override?.compactAtTokens),
    )
}

/** Pure: one role as the app sees it. */
fun roleView(role: AgentRole, boot: RoleConfig, stored: StoredOverride?): RoleModelView = RoleModelView(
    role = role.wireName,
    effective = (stored?.override?.applyTo(boot) ?: boot).toWire(),
    default = boot.toWire(),
    sources = sourcesOf(stored?.override),
    updatedAt = stored?.updatedAt,
)

/** Reads and changes the roles' model settings for the app. */
@Inject
@SingleIn(AppScope::class)
class ModelSettingsService(private val runtime: AgentRuntime) {
    private val settings get() = runtime.settings

    fun view(): ModelSettingsView = ModelSettingsView(
        roles = AgentRole.entries.map { roleView(it, settings.boot[it], settings.overrides[it]) },
        providersWithKeys = runtime.providersWithKeys.sorted().map { it.wireName },
    )

    /** Replaces [roleName]'s overrides with [body], if they are valid. */
    suspend fun update(roleName: String, body: RoleModelOverride): Either<ModelSettingsError, ModelSettingsView> {
        val role = agentRoleOf(roleName) ?: return ModelSettingsError.UnknownRole(roleName).left()
        val override = overrideFromWire(body).fold({ return invalid(it).left() }, { it })
        val errors = validateOverride(role, override, settings.boot[role], runtime.providersWithKeys)
        if (errors.isNotEmpty()) return invalid(errors).left()
        settings.set(role, override)
        log.info { "${role.wireName} model settings now ${settings[role].let { "${it.provider.wireName} ${it.model} effort=${it.effort}" }}" }
        return view().right()
    }

    /** Back to the server's defaults for every field of [roleName]. */
    suspend fun clear(roleName: String): Either<ModelSettingsError, ModelSettingsView> {
        val role = agentRoleOf(roleName) ?: return ModelSettingsError.UnknownRole(roleName).left()
        settings.clear(role)
        log.info { "${role.wireName} model settings back to the defaults" }
        return view().right()
    }

    private fun invalid(errors: List<SettingError>) = ModelSettingsError.Invalid(errors.map { FieldError(it.field, it.message) })
}

/** `GET /api/v1/settings/models`, `PUT` and `DELETE /api/v1/settings/models/{role}` (device-authenticated). */
@Inject
@ContributesIntoSet(AppScope::class)
class ModelSettingsRoutes(private val models: ModelSettingsService) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(ApiPaths.SETTINGS_MODELS) { call.respond(models.view()) }
                put("${ApiPaths.SETTINGS_MODELS}/{role}") {
                    call.respondEither(models.update(call.parameters["role"].orEmpty(), call.receive<RoleModelOverride>()))
                }
                delete("${ApiPaths.SETTINGS_MODELS}/{role}") {
                    call.respondEither(models.clear(call.parameters["role"].orEmpty()))
                }
            }
        }
    }
}
