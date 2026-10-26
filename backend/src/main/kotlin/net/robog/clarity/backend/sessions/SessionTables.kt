package net.robog.clarity.backend.sessions

import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.governance.Line
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

// Exposed table objects mirror db/migration/V6__sessions.sql. They are never used to create schema.

object AgentSessions : Table("agent_session") {
    val id = uuid("id")
    val kind = text("kind")
    val state = text("state")
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
    val stage = text("stage").nullable()
    val question = integer("question").nullable()
    val questionCount = integer("question_count").nullable()
    val capturedLine = jsonb<Line>("captured_line", ClarityJson).nullable()
    val heldActionId = text("held_action_id").nullable()
    val amendmentId = text("amendment_id").nullable()
    val snoozedUntil = timestamp("snoozed_until").nullable()
    val finalizing = bool("finalizing")
    val version = integer("version")
    val data = jsonb<SessionData>("data", ClarityJson)
    override val primaryKey = PrimaryKey(id)
}

object DayPlans : Table("day_plan") {
    val date = date("date")
    val priorities = jsonb<List<Chip>>("priorities", ClarityJson)
    val plan = jsonb<List<IfThen>>("plan", ClarityJson)
    val parked = jsonb<List<String>>("parked", ClarityJson)
    val called = bool("called")
    val sessionId = text("session_id").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(date)
}
