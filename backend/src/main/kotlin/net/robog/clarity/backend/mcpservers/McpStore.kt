package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** Mirrors db/migration/V44__mcp_servers.sql. */
object McpToolPolicies : Table("mcp_tool_policy") {
    val serverId = text("server_id")
    val tool = text("tool")
    val mode = text("mode")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(serverId, tool)
}

object McpServersSeen : Table("mcp_server_seen") {
    val serverId = text("server_id")
    val collection = text("collection")
    val firstSeen = timestamp("first_seen")
    override val primaryKey = PrimaryKey(serverId)
}

object McpSuggestions : Table("mcp_suggestion") {
    val id = uuid("id")
    val label = text("label")
    val labelKey = text("label_key")
    val why = text("why")
    val preset = text("preset").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object McpSuggestionsMade : Table("mcp_suggestion_made") {
    val labelKey = text("label_key")
    val createdAt = timestamp("created_at")
}

/** Pure: a label as compared for "the same server" (case and spacing ignored). */
fun labelKey(label: String): String = label.trim().lowercase().replace(Regex("\\s+"), " ")

/** The person's tool modes, the servers seen, and Clarity's suggestions. */
interface McpStore {
    /** Overrides by server id, then tool name. */
    suspend fun policies(): Map<String, Map<String, McpToolMode>>

    /** Sets the mode of one tool, or clears it (null) back to the default. */
    suspend fun setPolicy(serverId: String, tool: String, mode: McpToolMode?, at: Instant)

    /** Server id to the name of the collection made for it. */
    suspend fun seenCollections(): Map<String, String>

    /** Records [serverId] as seen with [collection]; false when it was already. */
    suspend fun markSeen(serverId: String, collection: String, at: Instant): Boolean

    suspend fun suggestions(): List<McpSuggestion>

    /** Stores a suggestion; null when one for the same label is open. */
    suspend fun addSuggestion(label: String, why: String, preset: String?, at: Instant): McpSuggestion?

    /** How many suggestions were made at or after [since], dismissed ones included. */
    suspend fun suggestionsMadeSince(since: Instant): Int

    suspend fun deleteSuggestion(id: String): Boolean
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedMcpStore(private val database: Database) : McpStore {
    override suspend fun policies(): Map<String, Map<String, McpToolMode>> = database.tx {
        McpToolPolicies.selectAll().mapNotNull { row ->
            val mode = modeOf(row[McpToolPolicies.mode]) ?: return@mapNotNull null
            Triple(row[McpToolPolicies.serverId], row[McpToolPolicies.tool], mode)
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }
    }

    override suspend fun setPolicy(serverId: String, tool: String, mode: McpToolMode?, at: Instant) {
        database.tx {
            if (mode == null) {
                McpToolPolicies.deleteWhere { (McpToolPolicies.serverId eq serverId) and (McpToolPolicies.tool eq tool) }
            } else {
                McpToolPolicies.upsert {
                    it[McpToolPolicies.serverId] = serverId
                    it[McpToolPolicies.tool] = tool
                    it[McpToolPolicies.mode] = wireOf(mode)
                    it[updatedAt] = at
                }
            }
        }
    }

    override suspend fun seenCollections(): Map<String, String> = database.tx {
        McpServersSeen.selectAll().associate { it[McpServersSeen.serverId] to it[McpServersSeen.collection] }
    }

    override suspend fun markSeen(serverId: String, collection: String, at: Instant): Boolean = database.tx {
        McpServersSeen.insertIgnore {
            it[McpServersSeen.serverId] = serverId
            it[McpServersSeen.collection] = collection
            it[firstSeen] = at
        }.insertedCount > 0
    }

    override suspend fun suggestions(): List<McpSuggestion> = database.tx {
        McpSuggestions.selectAll().orderBy(McpSuggestions.createdAt, SortOrder.DESC).map { row ->
            McpSuggestion(
                id = row[McpSuggestions.id].toString(),
                label = row[McpSuggestions.label],
                why = row[McpSuggestions.why],
                preset = row[McpSuggestions.preset],
                createdAt = row[McpSuggestions.createdAt].toString(),
            )
        }
    }

    override suspend fun addSuggestion(label: String, why: String, preset: String?, at: Instant): McpSuggestion? = database.tx {
        val id = Uuid.random()
        val key = labelKey(label)
        val inserted = McpSuggestions.insertIgnore {
            it[McpSuggestions.id] = id
            it[McpSuggestions.label] = label
            it[labelKey] = key
            it[McpSuggestions.why] = why
            it[McpSuggestions.preset] = preset
            it[createdAt] = at
        }.insertedCount > 0
        if (!inserted) return@tx null
        McpSuggestionsMade.insert {
            it[labelKey] = key
            it[createdAt] = at
        }
        McpSuggestion(id.toString(), label, why, preset, at.toString())
    }

    override suspend fun suggestionsMadeSince(since: Instant): Int = database.tx {
        McpSuggestionsMade.selectAll().where { McpSuggestionsMade.createdAt greaterEq since }.count().toInt()
    }

    override suspend fun deleteSuggestion(id: String): Boolean {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return false
        return database.tx { McpSuggestions.deleteWhere { McpSuggestions.id eq uuid } } > 0
    }
}

/** Pure: a mode's wire spelling (`auto`, `ask`, `off`). */
fun wireOf(mode: McpToolMode): String = mode.name.lowercase()

/** Pure: a mode from its wire spelling, or null. */
fun modeOf(wire: String): McpToolMode? = McpToolMode.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
