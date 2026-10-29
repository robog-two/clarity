package net.robog.clarity.backend.agent

import kotlinx.serialization.json.JsonElement
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Message
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ItemReaction
import net.robog.clarity.shared.noun.Handle
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

// Exposed table objects mirror db/migration/V4__lead.sql. They are never used to create schema.

object AgentThreads : Table("agent_thread") {
    val id = uuid("id")
    val createdAt = timestamp("created_at")
    val compactedAt = timestamp("compacted_at").nullable()
    val lastTurnAt = timestamp("last_turn_at").nullable()
    val lastUserInputAt = timestamp("last_user_input_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AgentJournal : Table("agent_journal") {
    val runId = text("run_id")
    val seq = integer("seq")
    val message = jsonb<Message>("message", HarnessJson)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(runId, seq)
}

object InboxItems : Table("inbox_item") {
    val seq = long("seq").autoIncrement()
    val id = uuid("id")
    val priority = text("priority")
    val item = jsonb<InboxItem>("item", ClarityJson)
    val dedupKey = text("dedup_key").nullable()
    val createdAt = timestamp("created_at")
    val consumedAt = timestamp("consumed_at").nullable()
    override val primaryKey = PrimaryKey(seq)
}

object FeedItems : Table("feed_item") {
    val seq = long("seq").autoIncrement()
    val id = uuid("id")
    val at = timestamp("at")
    val author = text("author")
    val sessionId = text("session_id").nullable()
    val cards = jsonb<List<Card>>("cards", ClarityJson)
    val streaming = bool("streaming")
    val clientInputId = text("client_input_id").nullable()
    val notify = text("notify").nullable()
    val batchState = text("batch_state").nullable()
    val releasedAt = timestamp("released_at").nullable()
    val notifyAbout = jsonb<List<Handle>>("notify_about", ClarityJson).nullable()
    val topic = text("topic").nullable()
    val reaction = jsonb<ItemReaction>("reaction", ClarityJson).nullable()
    override val primaryKey = PrimaryKey(seq)
}

object AgentTasks : Table("agent_task") {
    val id = uuid("id")
    val title = text("title")
    val detail = text("detail")
    val status = text("status")
    val heldActionId = text("held_action_id").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** V21: which helper runs worked on which task. */
object AgentTaskRuns : Table("agent_task_run") {
    val taskId = uuid("task_id")
    val runId = uuid("run_id")
    val how = text("how")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(taskId, runId)
}

object MemoryJournal : Table("memory_journal") {
    val id = long("id").autoIncrement()
    val date = date("date")
    val text = text("text")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object MemoryScratch : Table("memory_scratch") {
    val id = long("id").autoIncrement()
    val text = text("text")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ScheduledJobs : Table("scheduled_job") {
    val name = text("name")
    val nextRunAt = timestamp("next_run_at")
    val lastRunAt = timestamp("last_run_at").nullable()
    val payload = jsonb<JsonElement>("payload", ClarityJson).nullable()
    override val primaryKey = PrimaryKey(name)
}

object ToolResults : Table("tool_result") {
    val id = uuid("id")
    val runId = text("run_id")
    val toolUseId = text("tool_use_id")
    val toolName = text("tool_name")
    val content = text("content")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
