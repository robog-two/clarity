package net.robog.clarity.backend.connectors

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.ProblemException
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.connector.ConnectorTestReport
import net.robog.clarity.shared.connector.ConnectorTestRequest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb

/** Mirrors `connector_test` (V5). */
object ConnectorTestTable : Table("connector_test") {
    val connectorId = text("connector_id")
    val request = jsonb<ConnectorTestRequest>("request", ClarityJson)
    val requestedAt = timestamp("requested_at")
    val report = jsonb<ConnectorTestReport>("report", ClarityJson).nullable()
    override val primaryKey = PrimaryKey(connectorId)
}

/**
 * The "test this connection" round trip: the app asks ([request]), the connector sees the pending
 * request on its next action poll ([pendingFor]) and reports ([report]); the app reads the report
 * as `lastTest` in the connector list.
 */
@Inject
@SingleIn(AppScope::class)
class ConnectorTests(
    private val database: Database,
    private val clock: Clock,
    private val signals: OutboxSignals,
) {
    /** Starts a new test (replacing a pending one); the connector must exist. */
    suspend fun request(id: ConnectorId): Either<ConnectorError, ConnectorTestRequest> {
        val result: Either<ConnectorError, ConnectorTestRequest> = database.tx {
            if (Connectors.selectAll().where { Connectors.id eq id.value }.empty()) {
                ConnectorError.NotFound(id.value).left()
            } else {
                val now = clock.now()
                val request = ConnectorTestRequest(Uuid.random().toString(), now)
                ConnectorTestTable.upsert(ConnectorTestTable.connectorId) {
                    it[connectorId] = id.value
                    it[ConnectorTestTable.request] = request
                    it[requestedAt] = now
                }
                request.right()
            }
        }
        signals.signal()
        return result
    }

    /** The request this connector has not answered yet, if any. */
    suspend fun pendingFor(id: ConnectorId): ConnectorTestRequest? = database.tx {
        ConnectorTestTable.selectAll().where { ConnectorTestTable.connectorId eq id.value }.singleOrNull()?.let { row ->
            val request = row[ConnectorTestTable.request]
            request.takeIf { row[ConnectorTestTable.report]?.requestId != it.requestId }
        }
    }

    /** Records [report] if it answers the current request; stale or unknown reports are ignored (false). */
    suspend fun report(id: ConnectorId, report: ConnectorTestReport): Boolean = database.tx {
        val row = ConnectorTestTable.selectAll().where { ConnectorTestTable.connectorId eq id.value }.singleOrNull()
        if (row == null || row[ConnectorTestTable.request].requestId != report.requestId) {
            false
        } else {
            ConnectorTestTable.update({ ConnectorTestTable.connectorId eq id.value }) { it[ConnectorTestTable.report] = report }
            true
        }
    }

    /** The newest report per connector, for the connector list. */
    suspend fun lastReports(): Map<String, ConnectorTestReport> = database.tx {
        ConnectorTestTable.selectAll().mapNotNull { row -> row[ConnectorTestTable.report]?.let { row[ConnectorTestTable.connectorId] to it } }.toMap()
    }
}

/** `POST /api/v1/connectors/{id}/test` (app) and `GET`/`POST /ingest/v1/test` (connector). */
@Inject
@ContributesIntoSet(AppScope::class)
class ConnectorTestRoutes(private val tests: ConnectorTests) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                post("${ApiPaths.APP}/connectors/{id}/test") {
                    val raw = call.parameters["id"].orEmpty()
                    val result: Either<ConnectorError, ConnectorTestRequest> =
                        if (ConnectorId.PATTERN.matches(raw)) tests.request(ConnectorId(raw)) else ConnectorError.InvalidId(raw).left()
                    call.respondEither(result, HttpStatusCode.Accepted)
                }
            }
            authenticate(AuthProviders.CONNECTOR) {
                get(ApiPaths.TEST) {
                    val connector = call.principal<ConnectorPrincipal>()?.connectorId ?: throw ProblemException(EdgeProblem.Unauthorized())
                    val pending = tests.pendingFor(connector)
                    if (pending == null) call.respond(HttpStatusCode.NoContent) else call.respond(pending)
                }
                post(ApiPaths.TEST) {
                    val connector = call.principal<ConnectorPrincipal>()?.connectorId ?: throw ProblemException(EdgeProblem.Unauthorized())
                    val accepted = tests.report(connector, call.receive<ConnectorTestReport>())
                    call.respond(if (accepted) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
                }
            }
        }
    }
}
