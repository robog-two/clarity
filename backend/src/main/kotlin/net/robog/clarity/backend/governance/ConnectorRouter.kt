package net.robog.clarity.backend.governance

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** The operator's choice of connector for a kind when several declare it; none by default. */
fun interface ConnectorPreferences {
    fun preferred(kind: ActionKind): ConnectorId?
}

@Inject
@ContributesBinding(AppScope::class)
class NoConnectorPreferences : ConnectorPreferences {
    override fun preferred(kind: ActionKind): ConnectorId? = null
}

/** A connector as the router sees it: id, registration order and what it declared. */
data class Candidate(val id: ConnectorId, val manifest: ConnectorManifest)

/**
 * Pure: the connector that should execute [kind]. Exactly one should declare it; when several do,
 * the configured preference wins if it is among them, else the oldest (first in [candidates],
 * which are ordered by registration).
 */
fun selectConnector(kind: ActionKind, candidates: List<Candidate>, preferred: ConnectorId?): ConnectorId? {
    val capable = candidates.filter { kind in it.manifest.handles }
    return capable.firstOrNull { it.id == preferred }?.id ?: capable.firstOrNull()?.id
}

/** Picks the connector for an action by the `handles` in the manifests connectors announced. */
@Inject
@SingleIn(AppScope::class)
class ConnectorRouter(private val database: Database, private val preferences: ConnectorPreferences) {
    suspend fun select(kind: ActionKind): ConnectorId? = selectCandidate(kind)?.id

    /** The accounts the connector that executes [kind] announced ([ConnectorManifest.accounts]); empty when there is none. */
    suspend fun accounts(kind: ActionKind): List<ConnectorAccount> = selectCandidate(kind)?.manifest?.accounts.orEmpty()

    private suspend fun selectCandidate(kind: ActionKind): Candidate? {
        val candidates = database.tx {
            Connectors.selectAll().orderBy(Connectors.createdAt, SortOrder.ASC).mapNotNull { row ->
                row[Connectors.manifest]?.let { Candidate(ConnectorId(row[Connectors.id]), it) }
            }
        }
        val id = selectConnector(kind, candidates, preferences.preferred(kind)) ?: return null
        return candidates.first { it.id == id }
    }
}

/** The person's mail accounts an email can go from: label and address, as the mail connector announced them. */
fun interface MailAccounts {
    suspend fun list(): List<ConnectorAccount>
}

@Inject
@ContributesBinding(AppScope::class)
class RoutedMailAccounts(private val router: ConnectorRouter) : MailAccounts {
    override suspend fun list(): List<ConnectorAccount> = router.accounts(ActionKind.SEND_EMAIL)
}
