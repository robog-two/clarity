package net.robog.clarity.backend.evals

import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.SystemBlock
import net.robog.clarity.harness.ToolDefinition
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One Postgres for all scenario evals of a test JVM (each eval resets the tables it touches).
 * Stopped by a shutdown hook, so it is cleaned up even where the Testcontainers reaper is off.
 */
object EvalDb {
    const val MAIL = "eval-mail"

    private val postgres by lazy {
        PostgreSQLContainer("postgres:18-alpine").also { container ->
            container.start()
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { container.stop() } })
        }
    }

    val database: Database by lazy {
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        Database.connect(dataSource).also { db -> registerMailConnector(db) }
    }

    private fun registerMailConnector(db: Database) = runBlocking {
        db.tx {
            Connectors.insert {
                it[id] = MAIL
                it[provisioned] = true
                it[createdAt] = Clock.System.now()
                it[manifest] = ConnectorManifest(
                    "mail", "Mail", "1", setOf(Noun.MESSAGE),
                    handles = setOf(ActionKind.SAVE_DRAFT, ActionKind.SEND_EMAIL, ActionKind.EXTERNAL_TOOL, ActionKind.CREATE_EVENT),
                )
            }
        }
    }

    suspend fun rig(clock: Clock = Clock.System, apiKey: String? = "sk-test"): LeadRig = LeadRig(database, clock, apiKey).also { it.reset() }
}

/** The model answers from a queue, one response per model call. */
class Script(vararg initial: ModelResponse) {
    private val steps = ArrayDeque(initial.toList())
    fun add(vararg responses: ModelResponse) = steps.addAll(responses)
    fun next(): ModelResponse? = steps.removeFirstOrNull()
}

fun Message.text(): String = content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
fun ModelRequest.transcript(): String = messages.joinToString("\n---\n") { "${it.role}: ${it.text()}" }

/** What a prompt cache keys on: the tools and the system blocks. */
data class Prefix(val system: List<SystemBlock>, val tools: List<ToolDefinition>, val deferred: List<ToolDefinition>)

fun ModelRequest.prefix() = Prefix(system, tools, deferredTools)

fun sendEmail(id: String, to: String, subject: String = "Contract", body: String = "Signed copy attached.", reason: String = "asked"): ModelResponse =
    callTool(
        id, "send_email",
        buildJsonObject {
            put("to", JsonArray(listOf(JsonPrimitive(to))))
            put("subject", subject)
            put("body", body)
            put("reason", reason)
        },
    )
