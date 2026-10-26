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
import net.robog.clarity.backend.config.ModelProvider
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.COMPACTION_MIN_TRIGGER_TOKENS
import net.robog.clarity.harness.ModelGateway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** The largest `max_tokens` a role may ask for (current Claude models stream up to 128K). */
const val MAX_OUTPUT_TOKENS: Long = 128_000

/** `anthropic` / `openrouter`, as in env, yaml, the database and the API. */
val ModelProvider.wireName: String get() = name.lowercase()

fun modelProviderOf(raw: String): ModelProvider? = ModelProvider.entries.firstOrNull { it.wireName == raw.trim().lowercase() }

/** `lead` / `helper` / `guard` / `triage`. */
val AgentRole.wireName: String get() = name.lowercase()

fun agentRoleOf(raw: String): AgentRole? = AgentRole.entries.firstOrNull { it.wireName == raw.trim().lowercase() }

/** One role's runtime override (`model_role_override`); a null field keeps the deploy-time default. */
data class RoleOverride(
    val provider: ModelProvider? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxTokens: Long? = null,
    val cacheTtl: String? = null,
    val compaction: Boolean? = null,
    val compactAtTokens: Long? = null,
) {
    val isEmpty: Boolean get() = this == RoleOverride()

    /** Pure: [base] with every set field replaced. */
    fun applyTo(base: RoleConfig): RoleConfig = base.copy(
        provider = provider ?: base.provider,
        model = model ?: base.model,
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
 * Pure: what is wrong with [override] for [role] on top of [boot], given the providers that have a
 * key. Only the fields the override sets are checked (the boot config passed its own checks), plus
 * whether the model name fits the provider whenever either is set. Empty means it can be saved.
 */
fun validateOverride(role: AgentRole, override: RoleOverride, boot: RoleConfig, keys: Set<ModelProvider>): List<SettingError> = buildList {
    val effective = override.applyTo(boot)
    // Any role may run on OpenRouter; the lead then compacts its thread client-side.
    if (override.provider == ModelProvider.OPENROUTER && ModelProvider.OPENROUTER !in keys) {
        add(SettingError("provider", "this server has no OpenRouter key (set OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE)"))
    }
    override.model?.let {
        when {
            it.isBlank() -> add(SettingError("model", "pick a model"))
            it.any(Char::isWhitespace) -> add(SettingError("model", "a model name has no spaces"))
        }
    }
    if ((override.model != null || override.provider != null) && none { it.field == "model" }) {
        when {
            effective.provider == ModelProvider.OPENROUTER && '/' !in effective.model ->
                add(SettingError("model", "OpenRouter model names look like vendor/model, e.g. anthropic/claude-haiku-5.5"))
            effective.provider == ModelProvider.ANTHROPIC && '/' in effective.model ->
                add(SettingError("model", "Anthropic model names have no vendor part, e.g. claude-haiku-5-5"))
        }
    }
    override.effort?.let { if (it !in EFFORTS) add(SettingError("effort", "effort is one of ${EFFORTS.joinToString(", ")}")) }
    override.maxTokens?.let { if (it !in 1..MAX_OUTPUT_TOKENS) add(SettingError("maxTokens", "max tokens is 1 to $MAX_OUTPUT_TOKENS")) }
    override.cacheTtl?.let { if (it != "5m" && it != "1h") add(SettingError("cacheTtl", "the cache lasts 5m or 1h")) }
    override.compactAtTokens?.let {
        if (it < COMPACTION_MIN_TRIGGER_TOKENS) add(SettingError("compactAtTokens", "compaction starts at $COMPACTION_MIN_TRIGGER_TOKENS tokens or more"))
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

// Mirrors db/migration/V14__model_role_override.sql.
object ModelRoleOverrides : Table("model_role_override") {
    val role = text("role")
    val provider = text("provider").nullable()
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
            role to StoredOverride(
                RoleOverride(
                    provider = row[ModelRoleOverrides.provider]?.let(::modelProviderOf),
                    model = row[ModelRoleOverrides.model],
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
                it[provider] = override.provider?.wireName
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
 * A role's settings and the gateway of its provider, read together so the two always match. Taken
 * once per call (guard, triage, rule drafts) or once per run (a lead turn, a helper run).
 */
data class BoundRole(val role: AgentRole, val config: RoleConfig, val gateway: ModelGateway)
