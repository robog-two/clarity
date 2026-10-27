package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.EFFORTS

import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.tx
import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.harness.MIN_COMPACT_AT_TOKENS
import net.robog.clarity.harness.ModelGateway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

private val log = KotlinLogging.logger {}

/** The largest `max_tokens` a role may ask for. */
const val MAX_OUTPUT_TOKENS: Long = 128_000

/** Pure: whether [model] looks like an OpenRouter model id (`vendor/model`, no spaces). */
fun isOpenRouterModelId(model: String): Boolean = '/' in model && model.isNotBlank() && model.none(Char::isWhitespace)

/** `lead` / `helper` / `guard` / `triage`. */
val AgentRole.wireName: String get() = name.lowercase()

fun agentRoleOf(raw: String): AgentRole? = AgentRole.entries.firstOrNull { it.wireName == raw.trim().lowercase() }

/** One role's runtime override (`model_role_override`); a null field keeps the deploy-time default. */
data class RoleOverride(
    val model: String? = null,
    val effort: String? = null,
    val maxTokens: Long? = null,
    val cacheTtl: String? = null,
    val compaction: Boolean? = null,
    val compactAtTokens: Long? = null,
) {
    val isEmpty: Boolean get() = this == RoleOverride()

    /**
     * Pure: [base] with every set field replaced. A stored model that is not an OpenRouter id (one
     * saved for the former direct Anthropic API that migration V18 could not map) is ignored, so the
     * role falls back to its default instead of failing every call.
     */
    fun applyTo(base: RoleConfig): RoleConfig = base.copy(
        model = model?.takeIf(::isOpenRouterModelId) ?: base.model,
        effort = effort ?: base.effort,
        maxTokens = maxTokens ?: base.maxTokens,
        cacheTtl = cacheTtl ?: base.cacheTtl,
        compaction = compaction ?: base.compaction,
        compactAtTokens = compactAtTokens ?: base.compactAtTokens,
    )
}

/** Pure: the settings in effect: each role's override over its boot-time config (env, yaml, built-in default). */
fun effectiveRoles(boot: AgentRoles, overrides: Map<AgentRole, RoleOverride>): AgentRoles {
    fun role(r: AgentRole) = overrides[r]?.applyTo(boot[r]) ?: boot[r]
    return AgentRoles(lead = role(AgentRole.LEAD), helper = role(AgentRole.HELPER), guard = role(AgentRole.GUARD), triage = role(AgentRole.TRIAGE))
}

/** A setting that cannot be saved: which field, and why (shown to the person). */
data class SettingError(val field: String, val message: String)

/**
 * Pure: what is wrong with [override]. Only the fields it sets are checked (the boot config passed
 * its own checks). Empty means it can be saved.
 */
fun validateOverride(override: RoleOverride): List<SettingError> = buildList {
    override.model?.let {
        when {
            it.isBlank() -> add(SettingError("model", "pick a model"))
            it.any(Char::isWhitespace) -> add(SettingError("model", "a model name has no spaces"))
            '/' !in it -> add(SettingError("model", "OpenRouter model names look like vendor/model, e.g. anthropic/claude-haiku-5.5"))
        }
    }
    override.effort?.let { if (it !in EFFORTS) add(SettingError("effort", "effort is one of ${EFFORTS.joinToString(", ")}")) }
    override.maxTokens?.let { if (it !in 1..MAX_OUTPUT_TOKENS) add(SettingError("maxTokens", "max tokens is 1 to $MAX_OUTPUT_TOKENS")) }
    override.cacheTtl?.let { if (it != "5m" && it != "1h") add(SettingError("cacheTtl", "the cache lasts 5m or 1h")) }
    override.compactAtTokens?.let {
        if (it < MIN_COMPACT_AT_TOKENS) add(SettingError("compactAtTokens", "compaction starts at $MIN_COMPACT_AT_TOKENS tokens or more"))
    }
}

/** An override as stored, with when it last changed. */
data class StoredOverride(val override: RoleOverride, val updatedAt: Instant)

/** Where the overrides live; the database in production, memory in tests. */
interface RoleOverrideStore {
    suspend fun all(): Map<AgentRole, StoredOverride>
    suspend fun put(role: AgentRole, override: RoleOverride): StoredOverride
    suspend fun clear(role: AgentRole)
}

class InMemoryRoleOverrideStore(private val clock: Clock = Clock.System) : RoleOverrideStore {
    private val rows = ConcurrentHashMap<AgentRole, StoredOverride>()
    override suspend fun all(): Map<AgentRole, StoredOverride> = rows.toMap()
    override suspend fun put(role: AgentRole, override: RoleOverride) = StoredOverride(override, clock.now()).also { rows[role] = it }
    override suspend fun clear(role: AgentRole) {
        rows.remove(role)
    }
}

// Mirrors db/migration/V14__model_role_override.sql and V18__model_role_override_openrouter.sql (no provider column).
object ModelRoleOverrides : Table("model_role_override") {
    val role = text("role")
    val model = text("model").nullable()
    val effort = text("effort").nullable()
    val maxTokens = long("max_tokens").nullable()
    val cacheTtl = text("cache_ttl").nullable()
    val compaction = bool("compaction").nullable()
    val compactAtTokens = long("compact_at_tokens").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(role)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DbRoleOverrideStore(private val database: Database, private val clock: Clock) : RoleOverrideStore {
    override suspend fun all(): Map<AgentRole, StoredOverride> = database.tx {
        ModelRoleOverrides.selectAll().mapNotNull { row ->
            val role = agentRoleOf(row[ModelRoleOverrides.role]) ?: return@mapNotNull null
            val model = row[ModelRoleOverrides.model]
            if (model != null && !isOpenRouterModelId(model)) {
                log.warn { "the ${role.wireName} override's model '$model' is not an OpenRouter id; the role uses its default model" }
            }
            role to StoredOverride(
                RoleOverride(
                    model = model,
                    effort = row[ModelRoleOverrides.effort],
                    maxTokens = row[ModelRoleOverrides.maxTokens],
                    cacheTtl = row[ModelRoleOverrides.cacheTtl],
                    compaction = row[ModelRoleOverrides.compaction],
                    compactAtTokens = row[ModelRoleOverrides.compactAtTokens],
                ),
                row[ModelRoleOverrides.updatedAt],
            )
        }.toMap()
    }

    override suspend fun put(role: AgentRole, override: RoleOverride): StoredOverride {
        val now = clock.now()
        database.tx {
            ModelRoleOverrides.upsert(ModelRoleOverrides.role) {
                it[ModelRoleOverrides.role] = role.wireName
                it[model] = override.model
                it[effort] = override.effort
                it[maxTokens] = override.maxTokens
                it[cacheTtl] = override.cacheTtl
                it[compaction] = override.compaction
                it[compactAtTokens] = override.compactAtTokens
                it[updatedAt] = now
            }
        }
        return StoredOverride(override, now)
    }

    override suspend fun clear(role: AgentRole) {
        database.tx { ModelRoleOverrides.deleteWhere { ModelRoleOverrides.role eq role.wireName } }
    }
}

/**
 * The live model settings of every role: the boot config ([AgentConfig.roles], from env or yaml)
 * under the overrides set in the app. [roles] is the one place to read them, at the moment of a
 * call or the start of a run, never once at construction. Saving does not validate; callers check
 * with [validateOverride] first.
 */
@Inject
@SingleIn(AppScope::class)
class ModelSettings(config: AgentConfig, private val store: RoleOverrideStore) {
    /** The deploy-time defaults. */
    val boot: AgentRoles = config.roles

    private val lock = Mutex()
    private val stored = MutableStateFlow<Map<AgentRole, StoredOverride>>(emptyMap())
    private val current = MutableStateFlow(boot)

    val roles: StateFlow<AgentRoles> = current.asStateFlow()

    operator fun get(role: AgentRole): RoleConfig = current.value[role]

    /** The overrides in effect, by role. */
    val overrides: Map<AgentRole, StoredOverride> get() = stored.value

    /** Reads the stored overrides (at boot). */
    suspend fun load() = lock.withLock { publish(store.all()) }

    /** Replaces [role]'s overrides; an empty one clears them. */
    suspend fun set(role: AgentRole, override: RoleOverride) = lock.withLock {
        if (override.isEmpty) {
            store.clear(role)
            publish(stored.value - role)
        } else {
            publish(stored.value + (role to store.put(role, override)))
        }
    }

    /** Back to the deploy-time default for every field of [role]. */
    suspend fun clear(role: AgentRole) = set(role, RoleOverride())

    private fun publish(next: Map<AgentRole, StoredOverride>) {
        stored.value = next
        current.value = effectiveRoles(boot, next.mapValues { it.value.override })
    }
}

/** At boot, before the agents start: the overrides saved in the app. */
@Inject
@ContributesIntoSet(AppScope::class)
class ModelSettingsLoader(private val settings: ModelSettings) : StartupTask {
    override val name = "load-model-settings"

    override suspend fun run() = settings.load()
}

/**
 * A role's settings and the gateway, read together so the two always match. Taken

 * once per call (guard, triage, rule drafts) or once per run (a lead turn, a helper run).
 */
data class BoundRole(val role: AgentRole, val config: RoleConfig, val gateway: ModelGateway)
