package net.robog.clarity.backend.outings

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import net.robog.clarity.backend.persistence.Contacts
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** What the scout reads about the person's people. A seam: production [ExposedOutingPeople]. */
interface OutingPeople {
    /** Saved contacts (not deleted). */
    suspend fun contacts(): List<ContactRef>

    /** The messages sent or received at or after [since] with at most [MAX_DIRECT_RECIPIENTS] recipients, newest first (see [recentContacts]). */
    suspend fun messagesSince(since: Instant): List<MessageParties>

    /** [recentContacts] as of [now]. */
    suspend fun recent(now: Instant): List<RecentContact> = recentContacts(contacts(), messagesSince(now - COMPANY_WINDOW), now)
}

/** `jsonb_array_length(message.recipients) <= max`: lists are left out in SQL. */
private class RecipientCountAtMost(private val max: Int) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("jsonb_array_length(")
        append(Messages.recipients)
        append(") <= $max")
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedOutingPeople(private val database: Database) : OutingPeople {
    override suspend fun contacts(): List<ContactRef> = database.tx {
        Contacts.selectAll().where { Contacts.deletedAt.isNull() }.orderBy(Contacts.updatedAt, SortOrder.DESC).limit(MAX_CONTACTS)
            .map { ContactRef(it[Contacts.id].toString(), it[Contacts.displayName], it[Contacts.handles]) }
    }

    override suspend fun messagesSince(since: Instant): List<MessageParties> = database.tx {
        // Newest first with a cap: should there be more, the oldest of the 90 days are the ones left out.
        // Only the four columns the rule reads come back, and mailing lists are dropped in SQL.
        Messages.select(Messages.sentAt, Messages.account, Messages.sender, Messages.recipients)
            .where { (Messages.sentAt greaterEq since) and Messages.deletedAt.isNull() and RecipientCountAtMost(MAX_DIRECT_RECIPIENTS) }
            .orderBy(Messages.sentAt, SortOrder.DESC)
            .limit(MAX_MESSAGES)
            .map { row -> MessageParties(row[Messages.sentAt], row[Messages.sender], row[Messages.recipients], row[Messages.account]) }
    }

    private companion object {
        const val MAX_CONTACTS = 5000
        const val MAX_MESSAGES = 20_000
    }
}
