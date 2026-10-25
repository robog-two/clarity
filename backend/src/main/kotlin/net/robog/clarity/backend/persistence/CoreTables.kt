package net.robog.clarity.backend.persistence

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.query.TriggerSpec
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

// Exposed table objects mirror db/migration/*.sql. They are never used to create schema.

object Devices : Table("device") {
    val id = uuid("id")
    val name = text("name")
    val publicKey = binary("public_key")
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object EnrollmentTokens : Table("enrollment_token") {
    val id = uuid("id")
    val tokenHash = binary("token_hash")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Connectors : Table("connector") {
    val id = text("id")
    val keyHash = binary("key_hash").nullable()
    val provisioned = bool("provisioned")
    val manifest = jsonb<ConnectorManifest>("manifest", ClarityJson).nullable()
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ConnectorGrants : Table("connector_grant") {
    val connectorId = text("connector_id")
    val noun = text("noun")
    val state = text("state")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(connectorId, noun)
}

object ConnectorConfigs : Table("connector_config") {
    val connectorId = text("connector_id")
    val version = long("version")
    val values = jsonb<JsonObject>("config_values", ClarityJson)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(connectorId)
}

object SourceRecords : Table("source_record") {
    val connectorId = text("connector_id")
    val noun = text("noun")
    val sourceId = text("source_id")
    val sourceVersion = text("source_version")
    val receivedAt = timestamp("received_at")
    override val primaryKey = PrimaryKey(connectorId, noun, sourceId, sourceVersion)
}

object SourceLinks : Table("source_link") {
    val connectorId = text("connector_id")
    val noun = text("noun")
    val sourceId = text("source_id")
    val entityId = uuid("entity_id")
    val latestVersion = text("latest_version")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(connectorId, noun, sourceId)
}

object AgentTriggers : Table("agent_trigger") {
    val id = uuid("id")
    val name = text("name")
    val noun = text("noun")
    val spec = jsonb<TriggerSpec>("spec", ClarityJson)
    val enabled = bool("enabled")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Owned by the agent subsystem; declared here so every table object lives beside its migration. */
object AgentRuns : Table("agent_run") {
    val id = uuid("id")
    val reason = text("reason")
    val status = text("status")
    val task = jsonb<JsonElement>("task", ClarityJson).nullable()
    val transcript = jsonb<JsonElement>("transcript", ClarityJson).nullable()
    val usage = jsonb<JsonElement>("usage", ClarityJson).nullable()
    val error = text("error").nullable()
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object JobRuns : Table("job_run") {
    val name = text("name")
    val lastRunAt = timestamp("last_run_at")
    override val primaryKey = PrimaryKey(name)
}
