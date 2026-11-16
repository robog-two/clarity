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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.ExposedInboxStore
import net.robog.clarity.backend.agent.ExposedMemoryStore
import net.robog.clarity.backend.agent.ExposedRunStore
import net.robog.clarity.backend.agent.ExposedTaskStore
import net.robog.clarity.backend.agent.FailureGate
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxSignals
import net.robog.clarity.backend.agent.OwnStateTools
import net.robog.clarity.backend.agent.PgRunJournal
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.UNTRUSTED_SOURCE_TOOLS
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
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
import net.robog.clarity.backend.sources.SourceResolver
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.source.SourceItem
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
        database.tx {
            exec("TRUNCATE outing_idea, contact, message, calendar_event, inbox_item, feed_item, constitution_version, agent_journal RESTART IDENTITY CASCADE")
        }
    }

    // Monday 14:00 in New York.
    val now = Instant.parse("2026-11-16T19:00:00Z")
    val settings = Settings(homeZone = "America/New_York")

    class Rig(val database: Database, val now: Instant, val settings: Settings) {
        val clock = OutingClock(now)
        val reader = ExposedNounReader(database)
        val feedStore = ExposedFeedStore(database, clock)
        val feed = FeedService(feedStore, FeedHub())
        val inbox = ExposedInboxStore(database, clock, InboxSignals())
        val ideas = ExposedOutingStore(database, clock)
        val people = ExposedOutingPeople(database)
        val journal = PgRunJournal(database, clock)
        val constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), GovernanceEvents())
        val job = OutingScoutJob(reader, constitutions, ideas, people, inbox)
        val run = Uuid.random().toString()
        val outing = OutingTools(ideas, people, reader, SourceResolver(reader, { settings }, journal, ExposedRunStore(database, clock)), { settings }, clock)
        val tools = outing.all().associateBy { it.name }
        val own = OwnStateTools(
            feed, ExposedMemoryStore(database, clock), constitutions, ExposedTaskStore(database, clock, feed), { settings }, clock, FailureGate(null),
            SourceResolver(reader, { settings }, journal, ExposedRunStore(database, clock)),
        )
        val leadScope = RunScope("lead", isLead = true)
        val sendToUser = outing.gateSuggestions(own.sendToUser(leadScope), leadScope)

        /** The lead's turn answering the person (they wrote), with its own send_to_user. */
        val answeringScope = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        val sendWhenAsked = outing.gateSuggestions(own.sendToUser(answeringScope), answeringScope)

        suspend fun call(name: String, vararg pairs: Pair<String, String>): ToolResult =
            withContext(ToolCallContext(run, "call-${Uuid.random()}")) {
                tools.getValue(name).invoke(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
            }

        /** The lead read [content] with a record tool in an earlier step of its run. */
        suspend fun read(content: String, tool: String = "list_messages") {
            val use = Uuid.random().toString()
            journal.append(run, Message(Role.ASSISTANT, listOf(Block.ToolUse(use, tool, JsonObject(emptyMap())))))
            journal.append(run, Message(Role.USER, listOf(Block.Result(use, content, false))))
        }

        /** A suggestion for [ideaId], the way the lead posts one; [ref] and [kind] can be changed to try the gate. */
        suspend fun suggest(
            ideaId: String,
            ref: String? = "outing:$ideaId",
            kind: String = "look_forward",
            text: String = "Thursday evening is free.",
            toolUseId: String = "call-${Uuid.random()}",
            asked: Boolean = false,
        ): ToolResult {
            val cards = listOf(
                Card.Text(text),
                Card.Choices("Add it?", listOf(Choice("Add to calendar", "Add to calendar"), Choice("Not this time", "Not this time")), ref = ref),
            )
            val input = buildJsonObject {
                put("kind", kind)
                put("cards", ClarityJson.encodeToJsonElement(cards))
            }
            return withContext(ToolCallContext(run, toolUseId)) { (if (asked) sendWhenAsked else sendToUser).invoke(input) }
        }

        suspend fun fire(at: Instant = now, scheduledFor: Instant = at) =
            job.fire(FireContext(scheduledFor = scheduledFor, now = at, lastRunAt = null, settings = settings))

        suspend fun due(): List<InboxItem.JobDue> = inbox.pending().map { it.item }.filterIsInstance<InboxItem.JobDue>().filter { it.job == OUTING_JOB }

        suspend fun saveIdea(what: String, vararg more: Pair<String, String>): String =
            (call("save_outing_idea", "what" to what, *more).shouldBeInstanceOf<ToolResult.Ok>().content.split(" ")[1])

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
    }

    test("a saved idea, the scout and the lead's brief: only slots, saved ideas and recently messaged people") {
        val rig = Rig(database, now, settings)
        val sam = rig.person("Sam Example", "sam@example.com")
        val quiet = rig.person("Quiet Example", "quiet@example.com")
        val samMail = rig.mail("sam@example.com", daysAgo = 10)
        rig.mail("quiet@example.com", daysAgo = 120)
        rig.mail("pat@example.com", daysAgo = 1, received = false) // sent to someone who is not a saved contact
        rig.busy("2026-11-18T00:00:00Z", "2026-11-18T03:00:00Z") // Tuesday 19:00 to 22:00 in New York

        // company only from the last 90 days, no names in the text, and a source the run has read
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "withContactId" to quiet).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "90 days"
        rig.call("save_outing_idea", "what" to "Dinner with Sam Example").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "withContactId"
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to Uuid.random().toString()).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No record"
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to samMail).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "have not read"
        rig.read("""[{"id":"$samMail","subject":"Dinner?"}]""")
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

        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Dinner at the new place"
        listed shouldContain "with Sam Example"
        listed shouldContain "source record $samMail"

        // a retry of the same slot makes no second wake-up
        rig.fire()
        rig.due() shouldHaveSize 1

        // what list_outing_ideas returns is mail-derived text: it taints the turn like any mail tool
        ("list_outing_ideas" in UNTRUSTED_SOURCE_TOOLS) shouldBe true
    }

    test("every suggestion is a saved open idea, the server counts two a week, and the scout counts a little less") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night", "category" to "live music")
        val b = rig.saveIdea("Pottery class", "category" to "class")
        val c = rig.saveIdea("Hike at the lake", "category" to "hike")

        rig.suggest(a, ref = null).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "choices card"
        rig.suggest(Uuid.random().toString()).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "save_outing_idea"
        rig.suggest(a, ref = null, kind = "finding").shouldBeInstanceOf<ToolResult.Ok>() // not an outing: untouched by the gate
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 0

        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(c).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 2
        // two open questions in the feed, each tagged with its own idea
        val refs = rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD }.flatMap { it.cards }.filterIsInstance<Card.Choices>().map { it.ref }
        refs shouldBe listOf("outing:$a", "outing:$b")

        // the scout skips while both count
        rig.fire(at = now + 1.hours)
        rig.due().shouldBeEmpty()
        // a post at Tuesday 15:03 does not make next Tuesday 15:00 skip, while the server still refuses at that moment
        val almostWeek = now + 6.days + 23.hours + 30.minutes
        rig.fire(at = almostWeek)
        rig.due() shouldHaveSize 1
        rig.clock.now = almostWeek
        rig.suggest(c).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
        // a week and a minute later, both are old enough
        rig.clock.now = now + 7.days + 1.minutes
        rig.suggest(c).shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("a declined idea is never offered again, and nothing is paused") {
        val rig = Rig(database, now, settings)
        val id = rig.saveIdea("Jazz night at the Blue Room", "category" to "live music", "url" to "https://example.com/jazz")
        rig.fire()
        rig.due().single().instruction shouldContain "Saved ideas waiting: 1"
        rig.suggest(id).shouldBeInstanceOf<ToolResult.Ok>()

        // "Not this time"
        rig.call("update_outing_idea", "id" to id, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.ideas.all().single().state shouldBe IdeaState.DECLINED
        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Open ideas:\n(none)"
        listed shouldContain "Kinds declined: live music"
        listed shouldNotContain "Jazz night" // what is for the lead's own list only while open; the brief lines hold kinds

        // it cannot be offered again, nor saved again in other words or from the same page
        rig.suggest(id).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "declined"
        rig.call("save_outing_idea", "what" to "jazz night at the blue room").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "never suggested again"
        rig.call("save_outing_idea", "what" to "Late jazz", "url" to "https://example.com/jazz/").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "never suggested again"
        rig.call("update_outing_idea", "id" to id, "state" to "used").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "stays declined"
        rig.ideas.all().single().state shouldBe IdeaState.DECLINED

        // the very next scout run (the suggestion counts once, two are allowed) still offers time: no pause after a no
        rig.fire(at = now + 3.days, scheduledFor = now + 3.days)
        val offers = rig.due()
        offers shouldHaveSize 2
        offers.last().instruction shouldContain "Saved ideas waiting: 0"
        offers.last().instruction shouldNotContain "Jazz night"
    }

    test("an idea used for the calendar is recorded, and unknown ideas and states are refused") {
        val rig = Rig(database, now, settings)
        val id = rig.saveIdea("Pottery class")
        rig.call("update_outing_idea", "id" to id, "state" to "used").shouldBeInstanceOf<ToolResult.Ok>()
        rig.ideas.all().single().state shouldBe IdeaState.USED
        rig.call("update_outing_idea", "id" to id, "state" to "open").shouldBeInstanceOf<ToolResult.Error>()
        rig.call("update_outing_idea", "id" to Uuid.random().toString(), "state" to "declined").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No idea"
        rig.call("save_outing_idea", "what" to "Pottery class").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "calendar"
        rig.call("save_outing_idea", "what" to "x", "url" to "javascript:alert(1)").shouldBeInstanceOf<ToolResult.Error>()
        rig.call("save_outing_idea", "what" to "Pottery 2", "category" to "dinner at 8pm").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "generic words"
        rig.ideas.all().filter { it.state == IdeaState.OPEN }.shouldBeEmpty()
    }

    test("a single all-day event does not block the scout, a trip does") {
        val rig = Rig(database, now, settings)
        rig.busy("2026-11-17T00:00:00Z", "2026-11-18T00:00:00Z", allDay = true) // Tuesday only
        rig.fire()
        rig.due().single().instruction shouldContain "2026-11-17T18:00-05:00 (Tue)"
        // a trip over the whole window leaves nothing to offer
        rig.busy("2026-11-16T00:00:00Z", "2026-11-24T00:00:00Z", allDay = true)
        rig.fire(at = now + 1.hours, scheduledFor = now + 1.hours)
        rig.due() shouldHaveSize 1
    }

    test("the suggestion posts with a sources card once the lead read the idea; before that the source is refused") {
        val rig = Rig(database, now, settings)
        val samMail = rig.mail("sam@example.com", daysAgo = 10)
        rig.read("""[{"id":"$samMail"}]""")
        val id = rig.saveIdea("Dinner at the new place", "sourceId" to samMail)
        fun cards(): List<Card> = listOf(
            Card.Text("Thursday evening is free. Dinner at the new place with Sam Example?"),
            Card.Sources(listOf(SourceItem.Entity(samMail))),
            Card.Choices("Add it?", listOf(Choice("Add to calendar", "Add to calendar"), Choice("Not this time", "Not this time")), ref = "outing:$id"),
        )
        suspend fun post() = withContext(ToolCallContext(Uuid.random().toString(), "call-${Uuid.random()}")) {
            rig.sendToUser.invoke(buildJsonObject { put("kind", "look_forward"); put("cards", ClarityJson.encodeToJsonElement(cards())) })
        }
        // a run that never read the record cannot cite it
        post().shouldBeInstanceOf<ToolResult.Error>().message shouldContain samMail
        rig.ideas.offeredSince(now - 7.days).shouldBeEmpty()
        // the lead's run read the idea's list (which names its source record) in an earlier step
        val listed = (rig.call("list_outing_ideas") as ToolResult.Ok).content
        rig.read(listed, tool = "list_outing_ideas")
        withContext(ToolCallContext(rig.run, "call-${Uuid.random()}")) {
            rig.sendToUser.invoke(buildJsonObject { put("kind", "look_forward"); put("cards", ClarityJson.encodeToJsonElement(cards())) })
        }.shouldBeInstanceOf<ToolResult.Ok>()
        val posted = rig.feed.page(0, 10).items.single()
        posted.kind shouldBe MessageKind.LOOK_FORWARD
        posted.cards.map { it::class } shouldBe listOf(Card.Text::class, Card.Sources::class, Card.Choices::class)
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 1
    }
    test("an idea is offered once; a re-run of the same call is the same post; a ref gates any kind") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night", "category" to "live music")
        rig.suggest(a, toolUseId = "use-1").shouldBeInstanceOf<ToolResult.Ok>()
        // the same call again (a crash and re-run): let through, and the feed keeps one post
        rig.suggest(a, toolUseId = "use-1").shouldBeInstanceOf<ToolResult.Ok>()
        rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD } shouldHaveSize 1
        // a new call about the same idea is a re-offer
        rig.suggest(a, toolUseId = "use-2").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered"
        // a finding carrying an outing ref is gated too: with the budget spent it is refused
        val b = rig.saveIdea("Pottery class", "category" to "class")
        val c = rig.saveIdea("Hike at the lake", "category" to "hike")
        rig.suggest(b, kind = "finding").shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(c, kind = "finding").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
    }

    test("when the person asks for something to do, neither the ref nor the budget applies, and the offer is still remembered") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night", "category" to "live music")
        val b = rig.saveIdea("Pottery class", "category" to "class")
        val c = rig.saveIdea("Hike at the lake", "category" to "hike")
        val d = rig.saveIdea("Museum visit", "category" to "museum")
        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(c).shouldBeInstanceOf<ToolResult.Error>() // unprompted: the week's budget is used
        // "suggest something for tonight": a reply with no ref at all, and one with a ref
        rig.suggest(c, ref = null, kind = "look_forward", asked = true).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Ok>()
        // an answered offer is recorded (so it is never offered again) but is not counted against the unprompted budget
        rig.ideas.all().first { it.id == d }.suggestedAt shouldBe now
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 2
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered"
        rig.call("update_outing_idea", "id" to d, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "declined"
        // and the scout still sees two unprompted offers
        rig.fire(at = now + 1.hours)
        rig.due().shouldBeEmpty()
    }

    test("names are refused only for people who may be company, in the text or the address") {
        val rig = Rig(database, now, settings)
        rig.person("Sam Example", "sam@example.com")
        rig.mail("sam@example.com", daysAgo = 5)
        rig.person("Blue Room", "blueroom@example.com") // a saved contact, not messaged lately
        rig.call("save_outing_idea", "what" to "dinner with Sam").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "withContactId"
        rig.call("save_outing_idea", "what" to "Gig", "url" to "https://example.com/sam-and-friends").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "withContactId"
        rig.call("save_outing_idea", "what" to "Jazz at the Blue Room").shouldBeInstanceOf<ToolResult.Ok>()
    }
})
