package net.robog.clarity.backend.outings

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.agentTime
import net.robog.clarity.backend.agent.homeZone
import net.robog.clarity.backend.agent.readToolTime
import net.robog.clarity.backend.persistence.Contacts
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.handleKey
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Mirrors db/migration/V53__outing_idea.sql. */
object OutingIdeas : Table("outing_idea") {
    val id = uuid("id")
    val what = text("what")
    val place = text("place").nullable()
    val category = text("category").nullable()
    val windowFrom = timestamp("window_from").nullable()
    val windowTo = timestamp("window_to").nullable()
    val withContact = uuid("with_contact").nullable()
    val sourceId = uuid("source_id").nullable()
    val url = text("url").nullable()
    val state = text("state")
    val createdAt = timestamp("created_at")
    val decidedAt = timestamp("decided_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** What a new idea is made of. */
data class NewIdea(
    val what: String,
    val place: String? = null,
    val category: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val withContact: String? = null,
    val sourceId: String? = null,
    val url: String? = null,
)

/** The saved outing ideas (`outing_idea`). */
interface OutingStore {
    suspend fun save(idea: NewIdea): SavedIdea

    /** Every idea, newest first. */
    suspend fun all(): List<SavedIdea>

    /** Moves an open idea to [state]; null when it is unknown. Deciding again changes nothing but a declined idea stays declined. */
    suspend fun decide(id: String, state: IdeaState): SavedIdea?
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedOutingStore(private val database: Database, private val clock: Clock) : OutingStore {
    override suspend fun save(idea: NewIdea): SavedIdea {
        val id = Uuid.random()
        val now = clock.now()
        return database.tx {
            OutingIdeas.insert {
                it[OutingIdeas.id] = id
                it[what] = idea.what
                it[place] = idea.place
                it[category] = idea.category
                it[windowFrom] = idea.from
                it[windowTo] = idea.to
                it[withContact] = idea.withContact?.let(Uuid::parse)
                it[sourceId] = idea.sourceId?.let(Uuid::parse)
                it[url] = idea.url
                it[state] = IdeaState.OPEN.wire
                it[createdAt] = now
            }
            OutingIdeas.selectAll().where { OutingIdeas.id eq id }.single().toIdea()
        }
    }

    override suspend fun all(): List<SavedIdea> = database.tx {
        OutingIdeas.selectAll().orderBy(OutingIdeas.createdAt, SortOrder.DESC).map { it.toIdea() }
    }

    override suspend fun decide(id: String, state: IdeaState): SavedIdea? {
        val uuid = Uuid.parseOrNull(id) ?: return null
        val now = clock.now()
        return database.tx {
            val current = OutingIdeas.selectAll().where { OutingIdeas.id eq uuid }.forUpdate().singleOrNull()?.toIdea() ?: return@tx null
            // A no is final: nothing moves a declined idea back.
            if (current.state != IdeaState.DECLINED && current.state != state) {
                OutingIdeas.update({ OutingIdeas.id eq uuid }) {
                    it[OutingIdeas.state] = state.wire
                    it[decidedAt] = now
                }
            }
            OutingIdeas.selectAll().where { OutingIdeas.id eq uuid }.single().toIdea()
        }
    }
}

private fun ResultRow.toIdea() = SavedIdea(
    id = this[OutingIdeas.id].toString(),
    what = this[OutingIdeas.what],
    place = this[OutingIdeas.place],
    category = this[OutingIdeas.category],
    from = this[OutingIdeas.windowFrom],
    to = this[OutingIdeas.windowTo],
    withContact = this[OutingIdeas.withContact]?.toString(),
    sourceId = this[OutingIdeas.sourceId]?.toString(),
    url = this[OutingIdeas.url],
    state = IdeaState.of(this[OutingIdeas.state]) ?: IdeaState.OPEN,
    createdAt = this[OutingIdeas.createdAt],
)

/** What the scout reads about the person's people. A seam: production [ExposedOutingPeople]. */
interface OutingPeople {
    /** Saved contacts (not deleted). */
    suspend fun contacts(): List<ContactRef>

    /** The other parties of every message sent or received at or after [since]; the person's own address left out. */
    suspend fun messagesSince(since: Instant): List<MessageParties>

    /** [recentContacts] as of [now]. */
    suspend fun recent(now: Instant): List<RecentContact> = recentContacts(contacts(), messagesSince(now - COMPANY_WINDOW), now)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedOutingPeople(private val database: Database) : OutingPeople {
    override suspend fun contacts(): List<ContactRef> = database.tx {
        Contacts.selectAll().where { Contacts.deletedAt.isNull() }.limit(MAX_CONTACTS)
            .map { ContactRef(it[Contacts.id].toString(), it[Contacts.displayName], it[Contacts.handles]) }
    }

    override suspend fun messagesSince(since: Instant): List<MessageParties> = database.tx {
        Messages.select(Messages.sentAt, Messages.account, Messages.sender, Messages.recipients)
            .where { (Messages.sentAt greaterEq since) and Messages.deletedAt.isNull() }
            .limit(MAX_MESSAGES)
            .map { row ->
                val own = handleKey(row[Messages.account].value)
                MessageParties(
                    row[Messages.sentAt],
                    (listOf(row[Messages.sender]) + row[Messages.recipients]).filter { handleKey(it.value) != own },
                )
            }
    }

    private companion object {
        const val MAX_CONTACTS = 5000
        const val MAX_MESSAGES = 50_000
    }
}

private const val MAX_WHAT = 300
private const val MAX_FIELD = 200

/** The lead's outing tools: it saves ideas it spots or finds, lists them, and records the answer. */
@Inject
@SingleIn(AppScope::class)
class OutingTools(
    private val store: OutingStore,
    private val people: OutingPeople,
    private val reader: NounReader,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    fun all(): List<Tool> = listOf(saveIdea(), listIdeas(), updateIdea())

    @Serializable
    class SaveIdeaInput(
        @Description("What the outing is: \"Jazz night at the Blue Room\", \"dinner with Sam Example\".") val what: String,
        @Description("Where, if known.") val place: String? = null,
        @Description("The kind of outing in a few words (live music, dinner, hike, museum), so later suggestions can lean on what the person liked.") val category: String? = null,
        @Description("Earliest time it can happen, the person's local time, if it is bound to a date. $LOCAL_HINT") val from: String? = null,
        @Description("Latest time it can happen (an event's end), local time like `from`.") val until: String? = null,
        @Description("The contact id (from search_contacts) of a person to bring. Only people the person messaged in the last 90 days; nobody else.") val withContactId: String? = null,
        @Description("The id of the message or event it came from (a record a tool returned), so a suggestion can cite it.") val sourceId: String? = null,
        @Description("The event's page address, if it has one.") val url: String? = null,
    )

    private fun saveIdea(): Tool = tool<SaveIdeaInput>(
        name = "save_outing_idea",
        description = "Save an idea for something to look forward to: a gig in a newsletter, a friend saying \"we should get dinner\", an event a helper found. " +
            "The outing scout offers saved ideas when the person has free time. Saving sends nothing. An idea the person already said no to is refused: it is never suggested again.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        val what = input.what.trim().take(MAX_WHAT)
        val url = input.url?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null && !(url.startsWith("https://") || url.startsWith("http://")) || url?.any { it.isWhitespace() } == true) {
            return@tool ToolResult.Error("`url` must be an http(s) address.")
        }
        ideaRefusal(what, url, store.all())?.let { return@tool ToolResult.Error(it) }
        val from = input.from?.takeIf { it.isNotBlank() }?.let { readToolTime("from", it, zone).fold({ e -> return@tool ToolResult.Error(e) }, { t -> t }) }
        val to = input.until?.takeIf { it.isNotBlank() }?.let { readToolTime("until", it, zone).fold({ e -> return@tool ToolResult.Error(e) }, { t -> t }) }
        if (from != null && to != null && to <= from) return@tool ToolResult.Error("`until` must be after `from`.")
        val sourceId = input.sourceId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parsed = Uuid.parseOrNull(raw) ?: return@tool ToolResult.Error("`sourceId` is not a record id: \"$raw\".")
            reader.entity(EntityId(parsed.toString())) ?: return@tool ToolResult.Error("No record with id $raw.")
            parsed.toString()
        }
        val withContact = input.withContactId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parsed = Uuid.parseOrNull(raw)?.toString() ?: return@tool ToolResult.Error("`withContactId` is not a contact id: \"$raw\".")
            if (people.recent(clock.now()).none { it.id == parsed }) {
                return@tool ToolResult.Error("Only people the person exchanged messages with in the last 90 days can be company. Save the idea without `withContactId`.")
            }
            parsed
        }
        val saved = store.save(
            NewIdea(
                what, input.place?.trim()?.take(MAX_FIELD)?.ifEmpty { null }, input.category?.trim()?.take(MAX_FIELD)?.ifEmpty { null },
                from, to, withContact, sourceId, url,
            ),
        )
        ToolResult.Ok("Idea ${saved.id} saved. Nothing was sent; the scout offers it when the person has free time.")
    }

    @Serializable
    class NoInput

    private fun listIdeas(): Tool = tool<NoInput>(
        name = "list_outing_ideas",
        description = "List the saved outing ideas: the open ones (id, what, when, where, kind, company, source record), then the kinds the person used and declined. " +
            "Company shows only when that person was messaged in the last 90 days.",
        json = ClarityJson,
    ) {
        val zone = settings.homeZone()
        val now = clock.now()
        val ideas = store.all()
        val eligible = people.recent(now).associateBy { it.id }
        val open = ideas.filter { it.state == IdeaState.OPEN }.map { i ->
            buildString {
                append(i.id).append(" · ").append(i.what)
                i.category?.let { append(" · kind: ").append(it) }
                i.place?.let { append(" · at ").append(it) }
                if (i.from != null && i.to != null) append(" · ").append(agentTime(i.from, zone)).append(" until ").append(agentTime(i.to, zone))
                else i.from?.let { append(" · from ").append(agentTime(it, zone)) }
                i.withContact?.let { c -> eligible[c]?.let { append(" · with ").append(it.name).append(" (contact ").append(c).append(')') } }
                i.sourceId?.let { append(" · source record ").append(it) }
                i.url?.let { append(" · ").append(it) }
            }
        }
        fun kinds(state: IdeaState) = ideas.filter { it.state == state }.mapNotNull { it.category }.distinct().take(MAX_KINDS)
        fun whats(state: IdeaState) = ideas.filter { it.state == state }.take(MAX_LISTED).map { "${it.what}${it.category?.let { c -> " ($c)" }.orEmpty()}" }
        ToolResult.Ok(
            "Open ideas:\n" + (open.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "(none)") +
                "\nLiked (put on the calendar): " + (whats(IdeaState.USED).joinToString("; ").ifEmpty { "(none yet)" }) +
                "\nKinds liked: " + (kinds(IdeaState.USED).joinToString().ifEmpty { "(none yet)" }) +
                "\nDeclined, never suggest again: " + (whats(IdeaState.DECLINED).joinToString("; ").ifEmpty { "(none)" }) +
                "\nKinds declined: " + (kinds(IdeaState.DECLINED).joinToString().ifEmpty { "(none)" }),
        )
    }

    @Serializable
    class UpdateIdeaInput(
        @Description("The idea id from save_outing_idea or list_outing_ideas.") val id: String,
        @Description("used: it went on the calendar. declined: the person said no. A declined idea is never suggested again, and nothing is paused.") val state: String,
    )

    private fun updateIdea(): Tool = tool<UpdateIdeaInput>(
        name = "update_outing_idea",
        description = "Record the person's answer to an outing suggestion: used (added to the calendar) or declined (not this time).",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val state = IdeaState.of(input.state)?.takeIf { it != IdeaState.OPEN } ?: return@tool ToolResult.Error("`state` is used or declined.")
        val after = store.decide(input.id.trim(), state) ?: return@tool ToolResult.Error("No idea with id ${input.id}. See list_outing_ideas.")
        if (after.state == IdeaState.DECLINED && state == IdeaState.USED) {
            ToolResult.Error("\"${after.what}\" was declined and stays declined.")
        } else {
            ToolResult.Ok("\"${after.what}\" is now ${after.state.wire}.")
        }
    }

    private companion object {
        const val LOCAL_HINT = "The person's local time, e.g. 2026-11-19T19:00."
        const val MAX_KINDS = 10
        const val MAX_LISTED = 15
    }
}
