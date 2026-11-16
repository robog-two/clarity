package net.robog.clarity.backend.outings

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.encodeToJsonElement
import net.robog.clarity.backend.agent.ExposedInboxStore
import net.robog.clarity.backend.agent.ExposedMemoryStore
import net.robog.clarity.backend.agent.ExposedRunStore
import net.robog.clarity.backend.agent.ExposedTaskStore
import net.robog.clarity.backend.agent.FailureGate
import net.robog.clarity.backend.agent.OwnStateTools
import net.robog.clarity.backend.agent.PgRunJournal
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.sources.SourceResolver
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxSignals
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.CalendarEvents
import net.robog.clarity.backend.persistence.Contacts
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.OUTING_JOB
import net.robog.clarity.backend.scheduler.OutingScoutJob
import net.robog.clarity.backend.sources.idRead
import net.robog.clarity.backend.sources.readsOf
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.testcontainers.postgresql.PostgreSQLContainer

private class OutingClock(var now: Instant) : Clock {
    override fun now() = now
}

@EnabledIf(ContainerRuntimeAvailable::class)
class OutingDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest {
        database.tx { exec("TRUNCATE outing_idea, contact, message, calendar_event, inbox_item, feed_item, constitution_version RESTART IDENTITY CASCADE") }
    }

    // Monday 14:00 in New York.
    val now = Instant.parse("2026-11-16T19:00:00Z")
    val settings = Settings(homeZone = "America/New_York")

    class Rig(val database: Database, val now: Instant, val settings: Settings) {
        val clock = OutingClock(now)
        val reader = ExposedNounReader(database)
        val feedStore = ExposedFeedStore(database, clock)
        val inbox = ExposedInboxStore(database, clock, InboxSignals())
        val ideas = ExposedOutingStore(database, clock)
        val people = ExposedOutingPeople(database)
        val constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), GovernanceEvents())
        val job = OutingScoutJob(reader, constitutions, feedStore, ideas, people, inbox)
        val tools = OutingTools(ideas, people, reader, { settings }, clock).all().associateBy { it.name }

        suspend fun call(name: String, vararg pairs: Pair<String, String>): ToolResult =
            tools.getValue(name).invoke(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

        suspend fun fire(at: Instant = now, scheduledFor: Instant = at) =
            job.fire(FireContext(scheduledFor = scheduledFor, now = at, lastRunAt = null, settings = settings))

        suspend fun due(): List<InboxItem.JobDue> = inbox.pending().map { it.item }.filterIsInstance<InboxItem.JobDue>().filter { it.job == OUTING_JOB }

        suspend fun person(name: String, email: String): String {
            val id = Uuid.random()
            database.tx {
                Contacts.insert {
                    it[Contacts.id] = id
                    it[dedupKey] = Uuid.random().toByteArray()
                    it[firstSeenAt] = now
                    it[updatedAt] = now
                    it[displayName] = name
                    it[handles] = listOf(Handle.email(email, name))
                    it[searchText] = "$name $email".lowercase()
                }
            }
            return id.toString()
        }

        suspend fun mail(counterpart: String, daysAgo: Int, received: Boolean = true): String {
            val id = Uuid.random()
            val at = now - daysAgo.days
            val me = Handle.email("me@example.com")
            val other = Handle.email(counterpart)
            database.tx {
                Messages.insert {
                    it[Messages.id] = id
                    it[dedupKey] = Uuid.random().toByteArray()
                    it[firstSeenAt] = at
                    it[updatedAt] = at
                    it[channel] = "email"
                    it[account] = me
                    it[sender] = if (received) other else me
                    it[recipients] = listOf(if (received) me else other)
                    it[participants] = listOf(counterpart, "me@example.com")
                    it[subject] = "Dinner?"
                    it[body] = "We should get dinner."
                    it[sentAt] = at
                }
            }
            return id.toString()
        }

        suspend fun busy(startUtc: String, endUtc: String, allDay: Boolean = false) = database.tx {
            CalendarEvents.insert {
                it[id] = Uuid.random()
                it[dedupKey] = Uuid.random().toByteArray()
                it[firstSeenAt] = now
                it[updatedAt] = now
                it[calendar] = "personal"
                it[title] = "Busy"
                it[startAt] = Instant.parse(startUtc)
                it[endAt] = Instant.parse(endUtc)
                it[CalendarEvents.allDay] = allDay
                it[attendees] = emptyList()
            }
        }

        suspend fun postLookForward(at: Instant) {
            feedStore.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("Jazz?")), notify = "push_now", kind = MessageKind.LOOK_FORWARD))
                .also { database.tx { exec("UPDATE feed_item SET at = '$at' WHERE id = '${it.item.id.value}'") } }
        }
    }

    test("a saved idea, the scout and the lead's brief: only slots, saved ideas and recently messaged people") {
        val rig = Rig(database, now, settings)
        val sam = rig.person("Sam Example", "sam@example.com")
        val quiet = rig.person("Quiet Example", "quiet@example.com")
        val samMail = rig.mail("sam@example.com", daysAgo = 10)
        rig.mail("quiet@example.com", daysAgo = 120)
        rig.mail("pat@example.com", daysAgo = 1, received = false) // sent to someone who is not a saved contact
        rig.busy("2026-11-18T00:00:00Z", "2026-11-18T03:00:00Z") // Tuesday 19:00 to 22:00 in New York

        // company only from the last 90 days; the source record must exist
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "withContactId" to quiet).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "90 days"
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to Uuid.random().toString()).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No record"
        rig.call(
            "save_outing_idea", "what" to "Dinner at the new place", "category" to "dinner", "place" to "Main Street",
            "withContactId" to sam, "sourceId" to samMail,
        ).shouldBeInstanceOf<ToolResult.Ok>()

        rig.fire()
        val brief = rig.due().single().instruction
        brief shouldContain "2026-11-16T18:00-05:00 (Mon) until 2026-11-16T22:00-05:00 (Mon)" // tonight
        brief shouldContain "2026-11-19T18:00-05:00 (Thu)"
        brief shouldNotContain "2026-11-17T18:00" // Tuesday evening is taken: the event runs until 22:00
        brief shouldContain "Sam Example (contact $sam)"
        brief shouldNotContain "Quiet Example"
        brief shouldNotContain "pat@example.com"
        brief shouldContain "Saved ideas waiting: 1"
        brief shouldContain "web_search"

        // the lead reads the idea with its source record, which is what lets the sources card cite it
        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Dinner at the new place"
        listed shouldContain "with Sam Example"
        listed shouldContain "source record $samMail"
        val reads = readsOf(
            listOf(
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "list_outing_ideas", JsonObject(emptyMap())))),
                Message(Role.USER, listOf(Block.Result("t1", listed, false))),
            ),
        )
        idRead(samMail, reads) shouldBe true

        // a retry of the same slot makes no second wake-up
        rig.fire()
        rig.due() shouldHaveSize 1
    }

    test("two suggestions this week, or no free time, wake nobody") {
        val rig = Rig(database, now, settings)
        rig.postLookForward(now - 2.days)
        rig.fire()
        rig.due() shouldHaveSize 1 // one this week: one more is allowed

        rig.postLookForward(now - 1.days)
        rig.fire(at = now + 1.hours)
        rig.due() shouldHaveSize 1 // two this week: nothing new

        // a week later the first two are old enough; but a calendar full of all-day events leaves no slot
        val later = now + 8.days
        rig.busy("2026-11-24T00:00:00Z", "2026-12-02T00:00:00Z", allDay = true)
        rig.fire(at = later)
        rig.due() shouldHaveSize 1
    }

    test("a declined idea is never offered again, and nothing is paused") {
        val rig = Rig(database, now, settings)
        val id = (rig.call("save_outing_idea", "what" to "Jazz night at the Blue Room", "category" to "live music", "url" to "https://example.com/jazz")
            .shouldBeInstanceOf<ToolResult.Ok>().content.split(" ")[1])
        rig.fire()
        rig.due().single().instruction shouldContain "Saved ideas waiting: 1"

        // "Not this time"
        rig.call("update_outing_idea", "id" to id, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.ideas.all().single().state shouldBe IdeaState.DECLINED
        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Open ideas:\n(none)"
        listed shouldContain "Declined, never suggest again: Jazz night at the Blue Room (live music)"
        listed shouldContain "Kinds declined: live music"

        // the same idea, saved again in other words or from the same page, is refused
        rig.call("save_outing_idea", "what" to "jazz night at the blue room").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "never suggested again"
        rig.call("save_outing_idea", "what" to "Late jazz", "url" to "https://example.com/jazz/").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "never suggested again"
        // a yes cannot revive it
        rig.call("update_outing_idea", "id" to id, "state" to "used").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "stays declined"
        rig.ideas.all().single().state shouldBe IdeaState.DECLINED

        // the very next scout run still offers time: no pause after a no, but the idea is not among the open ones
        rig.fire(at = now + 3.days, scheduledFor = now + 3.days)
        val offers = rig.due()
        offers shouldHaveSize 2
        offers.last().instruction shouldContain "Saved ideas waiting: 0"
        offers.last().instruction shouldNotContain "Jazz night"
    }

    test("an idea used for the calendar is recorded, and unknown ideas and states are refused") {
        val rig = Rig(database, now, settings)
        val id = rig.call("save_outing_idea", "what" to "Pottery class").shouldBeInstanceOf<ToolResult.Ok>().content.split(" ")[1]
        rig.call("update_outing_idea", "id" to id, "state" to "used").shouldBeInstanceOf<ToolResult.Ok>()
        rig.ideas.all().single().state shouldBe IdeaState.USED
        rig.call("update_outing_idea", "id" to id, "state" to "open").shouldBeInstanceOf<ToolResult.Error>()
        rig.call("update_outing_idea", "id" to Uuid.random().toString(), "state" to "declined").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No idea"
        rig.call("save_outing_idea", "what" to "Pottery class").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "calendar"
        rig.call("save_outing_idea", "what" to "x", "url" to "javascript:alert(1)").shouldBeInstanceOf<ToolResult.Error>()
        rig.ideas.all().filter { it.state == IdeaState.OPEN }.shouldBeEmpty()
    }
    test("the suggestion posts with a sources card and one-tap choices once the lead read the idea; before that the source is refused") {
        val rig = Rig(database, now, settings)
        val samMail = rig.mail("sam@example.com", daysAgo = 10)
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to samMail).shouldBeInstanceOf<ToolResult.Ok>()
        val feed = FeedService(rig.feedStore, FeedHub())
        val journal = PgRunJournal(database, rig.clock)
        val own = OwnStateTools(
            feed, ExposedMemoryStore(database, rig.clock), rig.constitutions, ExposedTaskStore(database, rig.clock, feed), { settings }, rig.clock, FailureGate(null),
            SourceResolver(rig.reader, { settings }, journal, ExposedRunStore(database, rig.clock)),
        )
        val run = Uuid.random().toString()
        suspend fun suggest(): ToolResult {
            val input = buildJsonObject {
                put("kind", "look_forward")
                put(
                    "cards",
                    ClarityJson.encodeToJsonElement(
                        listOf(
                            Card.Text("Thursday evening is free. Dinner at the new place with Sam Example?"),
                            Card.Sources(listOf(SourceItem.Entity(samMail))),
                            Card.Choices("Add it?", listOf(Choice("Add to calendar", "Add to calendar"), Choice("Not this time", "Not this time"))),
                        ),
                    ),
                )
            }
            return withContext(ToolCallContext(run, "call-${Uuid.random()}")) { own.sendToUser(RunScope("lead", isLead = true)).invoke(input) }
        }
        suggest().shouldBeInstanceOf<ToolResult.Error>().message shouldContain samMail

        // the scout's wake-up alone is not a tool result; list_outing_ideas is
        val listed = (rig.call("list_outing_ideas") as ToolResult.Ok).content
        journal.append(run, Message(Role.ASSISTANT, listOf(Block.ToolUse("u1", "list_outing_ideas", JsonObject(emptyMap())))))
        journal.append(run, Message(Role.USER, listOf(Block.Result("u1", listed))))
        suggest().shouldBeInstanceOf<ToolResult.Ok>()
        val posted = feed.page(0, 10).items.single()
        posted.kind shouldBe MessageKind.LOOK_FORWARD
        posted.cards.map { it::class } shouldBe listOf(Card.Text::class, Card.Sources::class, Card.Choices::class)
        (posted.cards[2] as Card.Choices).options.map { it.label } shouldBe listOf("Add to calendar", "Not this time")
    }
})
