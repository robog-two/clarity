package net.robog.clarity.app.data

import net.robog.clarity.app.net.ModelsApi
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride

/** Which model each of the server's roles runs on, and changing it. */
interface ModelsRepository {
    suspend fun load(): ApiResult<ModelSettingsView>

    /** Replaces [role]'s overrides; null fields use the server's default. */
    suspend fun save(role: String, override: RoleModelOverride): ApiResult<ModelSettingsView>

    /** Back to the server's default for every setting of [role]. */
    suspend fun reset(role: String): ApiResult<ModelSettingsView>
}

class DefaultModelsRepository(private val api: ModelsApi) : ModelsRepository {
    override suspend fun load() = api.modelSettings()
    override suspend fun save(role: String, override: RoleModelOverride) = api.putRoleModel(role, override)
    override suspend fun reset(role: String) = api.resetRoleModel(role)
}
