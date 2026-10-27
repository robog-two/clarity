package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.vault.VAULT_TOOL_NAMES
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** One journaled message with its position and time. */
data class JournalRow(val seq: Int, val at: Instant, val message: Message)

/** A readable span of the conversation, ready to store. */
data class ChunkDraft(val fromSeq: Int, val toSeq: Int, val startedAt: Instant, val endedAt: Instant, val text: String, val untrusted: Boolean)

/** Tools whose calls and results never enter the archive: the vault and its codes. */
fun archiveExcluded(tool: String): Boolean =
    tool in VAULT_TOOL_NAMES || "credential" in tool || "vault" in tool || tool.endsWith("ask_code") || "password" in tool

/** Tools whose results are Clarity's own (confirmations); everything else read something from outside. */
private val OWN_RESULT_TOOLS = setOf(
    "send_to_user", "write_journal", "set_scratch", "update_task", "remember_fact", "replace_profile", "delegate", "message_helper",
    "cancel_helper", "list_helpers", "save_note", "manage_collection", "create_trigger", "delete_trigger", "read_journal", "read_profile",
    "update_plan", "capture", "set_priorities", "set_plan", "hand_off", "propose_line", "finalize_constitution", "propose_amendment", "stage_done",
    "save_draft", "send_email", "create_event", "move_event", "respond_to_invite", "create_reminder", "set_do_not_disturb", "list_triggers",
)

/**
 * Pure: the lead's journal before its latest compaction point, as readable chunks for search.
 * [rows] are consecutive journal messages that the model no longer sees. Kept: the person's words,
 * the agent's text, what it sent the person (send_to_user cards), tool names with short arguments,
 * truncated tool results (results from outside quoted as untrusted), inbox events. Left out:
 * thinking and other raw blocks, summaries and housekeeping, the memory context, effort messages,
 * and vault/credential/code tools entirely. Secret-looking text is redacted. A chunk ends at a turn
 * boundary once it has [target] characters, or anywhere at [max].
 */
fun chunkConversation(rows: List<JournalRow>, target: Int = 1800, max: Int = 3200): List<ChunkDraft> {
    val toolNames = HashMap<String, String>()
    val out = mutableListOf<ChunkDraft>()
    var lines = StringBuilder()
    var untrusted = false
    var first: JournalRow? = null
    var last: JournalRow? = null

    fun flush() {
        val f = first ?: return
        val l = last ?: return
        if (lines.isNotBlank()) out += ChunkDraft(f.seq, l.seq, f.at, l.at, lines.toString().trim(), untrusted)
        lines = StringBuilder()
        untrusted = false
        first = null
        last = null
    }

    for (row in rows) {
        val rendered = renderForArchive(row.message, toolNames)
        val startsTurn = row.message.role != Role.ASSISTANT && row.message.content.any { it is Block.Text }
        if (first != null && (lines.length >= max || (startsTurn && lines.length >= target))) flush()
        if (first == null) first = row
        last = row
        rendered.lines.forEach { lines.append(SecretDetector.redact(it)).append('\n') }
        untrusted = untrusted || rendered.untrusted
    }
    flush()
    return out
}

private class Rendered(val lines: List<String>, val untrusted: Boolean)

private fun renderForArchive(message: Message, toolNames: MutableMap<String, String>): Rendered {
    if (message.isSummary) return Rendered(emptyList(), false)
    var untrusted = false
    val lines = mutableListOf<String>()
    for (block in message.content) {
        when (block) {
            is Block.Text -> {
                val text = block.text.trim()
                if (text.isEmpty()) continue
                when (message.role) {
                    Role.USER -> if (!text.startsWith("(housekeeping")) lines += "Person: ${truncateQuoted(text, 1500)}"
                    Role.ASSISTANT -> lines += "Clarity (to itself): ${truncateQuoted(text, 1000)}"
                    Role.SYSTEM -> if (!text.startsWith("[memory")) {
                        if ("<untrusted" in text) untrusted = true
                        lines += "Event: ${truncateQuoted(text, 900)}"
                    }
                }
            }
            is Block.ToolUse -> {
                toolNames[block.id] = block.name
                if (archiveExcluded(block.name)) continue
                lines += if (block.name == "send_to_user") "Clarity to the person: ${truncateQuoted(cardText(block.input["cards"]), 1200)}"
                else "Clarity called ${block.name}(${argsText(block.input)})"
            }
            is Block.Result -> {
                val tool = toolNames[block.toolUseId] ?: "a tool"
                if (archiveExcluded(tool) || tool == "send_to_user") continue
                val content = block.content.trim().replace(Regex("\\s+"), " ")
                if (tool in OWN_RESULT_TOOLS) {
                    lines += "  $tool: ${truncateQuoted(content, 240)}"
                } else {
                    untrusted = true
                    lines += "  $tool returned: " + quoteUntrusted(UntrustedPart(tool, truncateQuoted(content, 400)))
                }
            }
            is Block.Raw -> Unit
        }
    }
    return Rendered(lines, untrusted)
}

/** Pure: a tool input as `key=value, …`, values cut short, at most 200 characters. */
private fun argsText(input: JsonObject): String =
    input.entries.joinToString(", ") { (k, v) -> "$k=${jsonShort(v).take(80)}" }.let { if (it.length > 200) it.take(199) + "…" else it }

private fun jsonShort(e: JsonElement): String = when (e) {
    is JsonPrimitive -> e.contentOrNull ?: "null"
    else -> e.toString()
}

private fun cardText(cards: JsonElement?): String = cards?.let { feedText(it.toString()) }.orEmpty()

/**
 * Archives the lead conversation that compaction took out of view into `conversation_chunk`:
 * everything after the last archived message (the cursor, `max(to_seq)`) and before the latest
 * compaction point (an Anthropic compaction block or a client-side summary). Idempotent: chunks are
 * keyed by `(run_id, from_seq)`, and a re-run starts at the cursor again. The first run backfills.
 */
@Inject
@SingleIn(AppScope::class)
class ConversationArchive(private val database: Database) {
    /** Archives what is new; returns the number of chunks written. */
    suspend fun indexCompacted(): Int {
        val runId = database.tx { AgentThreads.selectAll().orderBy(AgentThreads.createdAt).limit(1).singleOrNull()?.get(AgentThreads.id)?.toString() }
            ?: return 0
        return index(runId)
    }

    suspend fun index(runId: String): Int {
        val cursor = database.tx {
            jdbc().prepareStatement("SELECT max(to_seq) FROM conversation_chunk WHERE run_id = ?").use { st ->
                st.setString(1, runId)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1).takeUnless { rs.wasNull() } ?: 0 }
            }
        }
        val tail = database.tx {
            AgentJournal.selectAll().where { (AgentJournal.runId eq runId) and (AgentJournal.seq greater cursor) }
                .orderBy(AgentJournal.seq, SortOrder.ASC)
                .map { JournalRow(it[AgentJournal.seq], it[AgentJournal.createdAt], it[AgentJournal.message]) }
        }
        val cut = tail.indexOfLast { it.message.isCompactionPoint }
        if (cut <= 0) return 0
        val chunks = chunkConversation(tail.subList(0, cut))
        if (chunks.isEmpty()) return 0
        return database.tx {
            jdbc().prepareStatement(
                "INSERT INTO conversation_chunk (run_id, from_seq, to_seq, started_at, ended_at, text, untrusted) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (run_id, from_seq) DO NOTHING",
            ).use { st ->
                chunks.forEach { c ->
                    st.setString(1, runId)
                    st.setInt(2, c.fromSeq)
                    st.setInt(3, c.toSeq)
                    st.setTimestamp(4, java.sql.Timestamp.from(c.startedAt.toJavaInstant()))
                    st.setTimestamp(5, java.sql.Timestamp.from(c.endedAt.toJavaInstant()))
                    st.setString(6, c.text)
                    st.setBoolean(7, c.untrusted)
                    st.addBatch()
                }
                st.executeBatch().count { it > 0 }
            }
        }
    }

    /** Chunk [id] and the refs of its neighbours in the same run. */
    suspend fun around(id: Long): Triple<Long?, Long?, Long?> = database.tx {
        jdbc().prepareStatement(
            "SELECT c.id, (SELECT p.id FROM conversation_chunk p WHERE p.run_id = c.run_id AND p.from_seq < c.from_seq ORDER BY p.from_seq DESC LIMIT 1), " +
                "(SELECT x.id FROM conversation_chunk x WHERE x.run_id = c.run_id AND x.from_seq > c.from_seq ORDER BY x.from_seq LIMIT 1) " +
                "FROM conversation_chunk c WHERE c.id = ?",
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) Triple(null, null, null)
                else Triple(rs.getLong(1), rs.getLong(2).takeUnless { rs.wasNull() }, rs.getLong(3).takeUnless { rs.wasNull() })
            }
        }
    }
}
