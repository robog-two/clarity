package net.robog.clarity.shared.session

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire names of the model roles, shared so the app and server can't drift. */
object ModelRoles {
    const val LEAD = "lead"
    const val HELPER = "helper"
    const val GUARD = "guard"
    const val TRIAGE = "triage"
    val ALL: List<String> = listOf(LEAD, HELPER, GUARD, TRIAGE)

    /** The only model provider; still named on the wire for apps that show a provider. */
    const val OPENROUTER = "openrouter"

    /** The efforts a role can use, least first. */
    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")
}

/** One role's model settings in full. */
@Serializable
data class RoleModelSettings(
    /** An OpenRouter model id (`vendor/model`, e.g. `anthropic/claude-haiku-5.5`). */
    val model: String,
    val effort: String,
    val maxTokens: Long,
    /** `5m` or `1h`. */
    val cacheTtl: String,
    val compaction: Boolean,
    val compactAtTokens: Long,
    /** Always [ModelRoles.OPENROUTER]; kept because older apps require the field. */
    val provider: String = ModelRoles.OPENROUTER,
)

/**
 * `PUT /api/v1/settings/models/{role}`: the role's overrides, replacing any saved before. A null
 * field keeps the server's default (its environment or built-in setting).
 */
@Serializable
data class RoleModelOverride(
    /** Ignored: older apps send the provider they show; every model runs on OpenRouter. */
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxTokens: Long? = null,
    val cacheTtl: String? = null,
    val compaction: Boolean? = null,
    val compactAtTokens: Long? = null,
)

/** Where a setting in effect comes from. */
@Serializable
enum class SettingSource {
    /** Set in the app. */
    @SerialName("override") OVERRIDE,

    /** The server's default (environment, config file or built in). */
    @SerialName("default") DEFAULT,
}

/** One role as `GET /api/v1/settings/models` shows it. */
@Serializable
data class RoleModelView(
    /** One of [ModelRoles.ALL]. */
    val role: String,
    /** What the role uses now. */
    val effective: RoleModelSettings,
    /** What "reset to default" goes back to. */
    val default: RoleModelSettings,
    /** Each [RoleModelSettings] field name to where its value in [effective] comes from. */
    val sources: Map<String, SettingSource>,
    /** When the overrides last changed; null without any. */
    val updatedAt: Instant? = null,
)

/** `GET /api/v1/settings/models` (and the answer to a change): every role. */
@Serializable
data class ModelSettingsView(
    val roles: List<RoleModelView>,
    /** Always `[openrouter]` (the server does not start without its key); kept because older apps require the field. */
    val providersWithKeys: List<String> = listOf(ModelRoles.OPENROUTER),
    val efforts: List<String> = ModelRoles.EFFORTS,
)
