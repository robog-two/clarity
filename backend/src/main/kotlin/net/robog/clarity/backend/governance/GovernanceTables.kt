package net.robog.clarity.backend.governance

import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.ProposedAction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

// Exposed table objects mirror db/migration/V3__governance.sql. They are never used to create schema.

object ConstitutionVersions : Table("constitution_version") {
    val version = integer("version")
    val document = jsonb<Constitution>("document", ClarityJson)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(version)
}

object HeldActions : Table("held_action") {
    val id = uuid("id")
    val idempotencyKey = text("idempotency_key")
    val createdAt = timestamp("created_at")
    val action = jsonb<ProposedAction>("action", ClarityJson)
    val originalAction = jsonb<ProposedAction>("original_action", ClarityJson).nullable()
    val verdict = jsonb<GuardVerdict>("verdict", ClarityJson)
    val agentView = text("agent_view")
    val context = jsonb<List<ContextItem>>("context", ClarityJson)
    val urgency = text("urgency")
    val status = text("status")
    val decidedAt = timestamp("decided_at").nullable()
    val failure = text("failure").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Amendments : Table("amendment") {
    val id = uuid("id")
    val lineId = text("line_id").nullable()
    val oldText = text("old_text").nullable()
    val proposed = jsonb<Line>("proposed", ClarityJson)
    val reason = text("reason")
    val fromHeld = uuid("from_held").nullable()
    val status = text("status")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ActionOutboxTable : Table("action_outbox") {
    val id = uuid("id")
    val connectorId = text("connector_id")
    val heldId = uuid("held_id").nullable()
    val action = jsonb<ProposedAction>("action", ClarityJson)
    val idempotencyKey = text("idempotency_key")
    val status = text("status")
    val leaseUntil = timestamp("lease_until").nullable()
    val attempts = integer("attempts")
    val nextAttemptAt = timestamp("next_attempt_at")
    val result = jsonb<ActionOutcome>("result", ClarityJson).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ActivityTable : Table("activity") {
    val id = long("id").autoIncrement()
    val at = timestamp("at")
    val summary = text("summary")
    val kind = text("kind")
    val ok = bool("ok")
    val actionId = uuid("action_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
