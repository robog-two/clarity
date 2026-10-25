package net.robog.clarity.backend.connectors

import net.robog.clarity.backend.persistence.ConnectorGrants
import net.robog.clarity.backend.persistence.grantStateOrNull
import net.robog.clarity.backend.persistence.nounOrNull
import net.robog.clarity.backend.persistence.wireName
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll

/** All grant states of a connector. Read-only: the ingest path only ever calls this. */
fun JdbcTransaction.grantsOf(connectorId: ConnectorId): Map<Noun, GrantState> =
    ConnectorGrants.selectAll().where { ConnectorGrants.connectorId eq connectorId.value }
        .mapNotNull { row ->
            val noun = nounOrNull(row[ConnectorGrants.noun])
            val state = grantStateOrNull(row[ConnectorGrants.state])
            if (noun != null && state != null) noun to state else null
        }
        .toMap()

/** The nouns the user has granted to [connectorId]; ingest accepts only these. */
fun JdbcTransaction.grantedNouns(connectorId: ConnectorId): Set<Noun> =
    ConnectorGrants.selectAll()
        .where { (ConnectorGrants.connectorId eq connectorId.value) and (ConnectorGrants.state eq GrantState.GRANTED.wireName) }
        .mapNotNull { nounOrNull(it[ConnectorGrants.noun]) }
        .toSet()
