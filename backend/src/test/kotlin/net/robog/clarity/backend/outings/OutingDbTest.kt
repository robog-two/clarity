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
import kotlinx.coroutines.async
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
            exec("TRUNCATE plan_item, outing_idea, contact, message, calendar_event, inbox_item, feed_item, constitution_version, agent_journal RESTART IDENTITY CASCADE")
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
        val planItems = net.robog.clarity.backend.sessions.PlanItemStore(database)
        val refGate = net.robog.clarity.backend.agent.CardRefGate(planItems)
        val leadScope = RunScope("lead", isLead = true)
        val sendToUser = outing.gateSuggestions(refGate.wrap(own.sendToUser(leadScope)), leadScope)

        /** The lead's turn answering the person (they wrote), with its own send_to_user. */
        val answeringScope = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        val sendWhenAsked = outing.gateSuggestions(refGate.wrap(own.sendToUser(answeringScope)), answeringScope)

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

        /** send_to_user as the lead's turn [scope] would have it, gated. */
        fun sendAs(scope: RunScope) = outing.gateSuggestions(refGate.wrap(own.sendToUser(scope)), scope)

        suspend fun postVia(send: net.robog.clarity.harness.Tool, ideaRef: String?, kind: String = "look_forward", toolUseId: String = "call-${Uuid.random()}"): ToolResult {
            val cards = listOf(
                Card.Text("Thursday evening is free."),
                Card.Choices("Add it?", listOf(Choice("Add to calendar", "Add to calendar"), Choice("Not this time", "Not this time")), ref = ideaRef),
            )
            val input = buildJsonObject { put("kind", kind); put("cards", ClarityJson.encodeToJsonElement(cards)) }
            return withContext(ToolCallContext(run, toolUseId)) { send.invoke(input) }
        }

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
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to Uuid.random().toString()).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No record"
        rig.call("save_outing_idea", "what" to "Dinner at the new place", "sourceId" to samMail).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "have not read"
        rig.read("""[{"id":"$samMail","subject":"Dinner?"}]""")
        rig.call(
            "save_outing_idea", "what" to "Dinner at the new place", "category" to "food_drink", "place" to "Main Street",
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
        val a = rig.saveIdea("Jazz night", "category" to "music")
        val b = rig.saveIdea("Pottery class", "category" to "learning")
        val c = rig.saveIdea("Hike at the lake", "category" to "outdoors")

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
        val id = rig.saveIdea("Jazz night at the Blue Room", "category" to "music", "url" to "https://example.com/jazz")
        rig.fire()
        rig.due().single().instruction shouldContain "Saved ideas waiting: 1"
        rig.suggest(id).shouldBeInstanceOf<ToolResult.Ok>()

        // "Not this time"
        rig.call("update_outing_idea", "id" to id, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.ideas.all().single().state shouldBe IdeaState.DECLINED
        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Open ideas:\n(none)"
        listed shouldContain "Kinds declined: music"
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
        rig.call("save_outing_idea", "what" to "Pottery 2", "category" to "x".repeat(41)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "at most 40"
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
        val a = rig.saveIdea("Jazz night", "category" to "music")
        rig.suggest(a, toolUseId = "use-1").shouldBeInstanceOf<ToolResult.Ok>()
        // the same call again (a crash and re-run): let through, and the feed keeps one post
        rig.suggest(a, toolUseId = "use-1").shouldBeInstanceOf<ToolResult.Ok>()
        rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD } shouldHaveSize 1
        // a new call about the same idea is a re-offer
        rig.suggest(a, toolUseId = "use-2").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered"
        // a finding carrying an outing ref is gated too: with the budget spent it is refused
        val b = rig.saveIdea("Pottery class", "category" to "learning")
        val c = rig.saveIdea("Hike at the lake", "category" to "outdoors")
        rig.suggest(b, kind = "finding").shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(c, kind = "finding").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
    }

    test("when the person asks for something to do, neither the ref nor the budget applies, and the offer is still remembered") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night", "category" to "music")
        val b = rig.saveIdea("Pottery class", "category" to "learning")
        val c = rig.saveIdea("Hike at the lake", "category" to "outdoors")
        val d = rig.saveIdea("Museum visit", "category" to "art_museums")
        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(c).shouldBeInstanceOf<ToolResult.Error>() // unprompted: the week's budget is used
        // "suggest something for tonight": a reply with no ref at all, and one with a ref
        rig.suggest(c, ref = null, kind = "look_forward", asked = true).shouldBeInstanceOf<ToolResult.Error>() // a look_forward needs its ref in every turn
        rig.suggest(c, ref = null, kind = "finding", asked = true).shouldBeInstanceOf<ToolResult.Ok>() // a plain reply is not an outing post
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Ok>()
        // an answered offer is recorded (so it is never offered again) but is not counted against the unprompted budget
        rig.ideas.all().first { it.id == d }.suggestedAt shouldBe now
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 2
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Ok>() // an open idea already offered may be shown again when asked
        rig.call("update_outing_idea", "id" to d, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.answeringScope.startTurn(untrusted = false, requestedByUser = true) // a new turn
        rig.suggest(d, asked = true).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "declined"
        // and the scout still sees two unprompted offers
        rig.fire(at = now + 1.hours)
        rig.due().shouldBeEmpty()
    }

    test("the kind is short free text, and only kinds reach the kinds lines") {
        val rig = Rig(database, now, settings)
        rig.call("save_outing_idea", "what" to "Gig", "category" to "x".repeat(41)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "at most 40"
        val id = rig.saveIdea("Gig with the band", "category" to "Live Music")
        rig.call("update_outing_idea", "id" to id, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        val listed = rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "Kinds declined: live music"
        listed shouldNotContain "band"
    }

    test("a turn the person started skips the weekly budget even after reading ideas; a job turn does not; re-showing needs an untainted person turn") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night", "category" to "music")
        val b = rig.saveIdea("Pottery class", "category" to "learning")
        val c = rig.saveIdea("Hike at the lake", "category" to "outdoors")
        val d = rig.saveIdea("Museum visit", "category" to "art_museums")
        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b).shouldBeInstanceOf<ToolResult.Ok>() // the week's unprompted budget is used

        // "suggest something for tonight": the person's turn reads the ideas (which taints it), then offers one
        val person = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        rig.call("list_outing_ideas").shouldBeInstanceOf<ToolResult.Ok>()
        person.taint()
        rig.postVia(rig.sendAs(person), "outing:$c").shouldBeInstanceOf<ToolResult.Ok>()
        // not counted against the unprompted budget, but recorded
        rig.ideas.all().first { it.id == c }.suggestedAt shouldBe now
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 2
        // the other rules hold in this turn too: a look_forward needs its ref, the idea must be open, one outing ref per post
        rig.postVia(rig.sendAs(person), null).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "choices card"
        rig.postVia(rig.sendAs(person), "outing:${Uuid.random()}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No saved idea"
        // an idea offered before is not shown again in a tainted turn
        rig.postVia(rig.sendAs(person), "outing:$a").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered"

        // a job turn is still gated, and so is a job turn the person interrupted
        val job = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = false) }
        rig.postVia(rig.sendAs(job), "outing:$d").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
        val interrupted = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = false); it.userAsked() }
        interrupted.requestedByUser shouldBe true
        rig.postVia(rig.sendAs(interrupted), null).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "choices card"
        rig.postVia(rig.sendAs(interrupted), "outing:$d").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"

        // an untainted person turn may show an open, already offered idea again, never a declined one
        val clean = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        rig.postVia(rig.sendAs(clean), "outing:$a").shouldBeInstanceOf<ToolResult.Ok>()
        rig.call("update_outing_idea", "id" to a, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        rig.postVia(rig.sendAs(clean), "outing:$a").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "declined"
    }

    test("a malformed outing ref is refused, not let through ungated") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        listOf("outing:", "outing:abc", "outing:${a}x", "outing:${a.uppercase()}", "outing:$a and more").forEach { ref ->
            rig.postVia(rig.sendToUser, ref, kind = "finding").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "exactly"
        }
        rig.feed.page(0, 20).items.shouldBeEmpty()
    }

    test("two offers racing past the weekly budget cannot both win, and a failed post gives the reservation back") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>() // one of two used
        val others = (1..6).map { rig.saveIdea("Outing $it") }
        val results = kotlinx.coroutines.coroutineScope {
            val scope = this
            others.map { id -> scope.async(kotlinx.coroutines.Dispatchers.IO) { rig.suggest(id) } }.map { it.await() }
        }
        results.count { it is ToolResult.Ok } shouldBe 1
        rig.ideas.offeredSince(now - 7.days) shouldHaveSize 2

        // a post that fails after the reservation releases it
        database.tx { exec("TRUNCATE outing_idea, feed_item RESTART IDENTITY CASCADE") }
        val rig2 = Rig(database, now, settings)
        val b = rig2.saveIdea("Hike")
        val failing = rig2.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by rig2.own.sendToUser(rig2.leadScope) {
                override suspend fun invoke(input: JsonObject): ToolResult = ToolResult.Error("could not post")
            },
            rig2.leadScope,
        )
        rig2.postVia(failing, "outing:$b").shouldBeInstanceOf<ToolResult.Error>().message shouldBe "could not post"
        rig2.ideas.all().first { it.id == b }.suggestedAt shouldBe null
        rig2.suggest(b).shouldBeInstanceOf<ToolResult.Ok>()
    }
    test("a post that failed gives the reservation back, a post that reached the feed keeps it, also on cancellation") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        val failing = rig.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by rig.own.sendToUser(rig.leadScope) {
                override suspend fun invoke(input: JsonObject): ToolResult = ToolResult.Error("could not post")
            },
            rig.leadScope,
        )
        rig.postVia(failing, "outing:$a").shouldBeInstanceOf<ToolResult.Error>().message shouldBe "could not post"
        rig.ideas.all().single().suggestedAt shouldBe null

        // cancelled before anything was posted: released too, even though the coroutine is cancelled
        val cancelledEarly = rig.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by rig.own.sendToUser(rig.leadScope) {
                override suspend fun invoke(input: JsonObject): ToolResult = throw kotlinx.coroutines.CancellationException("stopped")
            },
            rig.leadScope,
        )
        runCatching { rig.postVia(cancelledEarly, "outing:$a") }.exceptionOrNull().shouldBeInstanceOf<kotlinx.coroutines.CancellationException>()
        rig.ideas.all().single().suggestedAt shouldBe null

        // cancelled after the post reached the feed: the idea stays reserved, so it is not offered twice
        val real = rig.own.sendToUser(rig.leadScope)
        val cancelledLate = rig.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by real {
                override suspend fun invoke(input: JsonObject): ToolResult {
                    real.invoke(input)
                    throw kotlinx.coroutines.CancellationException("stopped after the post")
                }
            },
            rig.leadScope,
        )
        runCatching { rig.postVia(cancelledLate, "outing:$a", toolUseId = "late-1") }.exceptionOrNull().shouldBeInstanceOf<kotlinx.coroutines.CancellationException>()
        rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD } shouldHaveSize 1
        rig.ideas.all().single().suggestedAt shouldBe now
        // the same idea from another call is a re-offer, and the retry of the same call still goes through
        rig.postVia(rig.sendToUser, "outing:$a", toolUseId = "other").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered"
        rig.postVia(rig.sendToUser, "outing:$a", toolUseId = "late-1").shouldBeInstanceOf<ToolResult.Ok>()
        rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD } shouldHaveSize 1
    }

    test("a turn the person started may make two offers and no more, however many ideas it read") {
        val rig = Rig(database, now, settings)
        val ids = (1..3).map { rig.saveIdea("Outing $it") }
        val person = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true); it.taint() }
        rig.postVia(rig.sendAs(person), "outing:${ids[0]}").shouldBeInstanceOf<ToolResult.Ok>()
        rig.postVia(rig.sendAs(person), "outing:${ids[1]}").shouldBeInstanceOf<ToolResult.Ok>()
        rig.postVia(rig.sendAs(person), "outing:${ids[2]}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already offered in this turn"
        rig.ideas.all().first { it.id == ids[2] }.suggestedAt shouldBe null
        rig.ideas.offeredSince(now - 7.days).shouldBeEmpty() // none counted against the unprompted budget
        // the next turn starts again
        person.startTurn(untrusted = false, requestedByUser = true)
        rig.postVia(rig.sendAs(person), "outing:${ids[2]}").shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("the gate fails closed: unreadable cards, a prefix in another case, or an outing mention it cannot parse") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        suspend fun raw(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): ToolResult =
            withContext(ToolCallContext(rig.run, "call-${Uuid.random()}")) { rig.sendToUser.invoke(JsonObject(mapOf(*pairs))) }
        val prim = kotlinx.serialization.json.JsonPrimitive("look_forward")
        raw("kind" to prim).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "could not be read"
        raw("kind" to prim, "cards" to kotlinx.serialization.json.JsonPrimitive("outing:$a")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "could not be read"
        raw("kind" to kotlinx.serialization.json.JsonPrimitive("finding"), "cards" to kotlinx.serialization.json.JsonPrimitive("Outing:$a"))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "could not be read"
        listOf("Outing:$a", "OUTING:$a", " outing:$a ".replace("outing", "Outing")).forEach { ref ->
            rig.postVia(rig.sendToUser, ref, kind = "finding").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "lower case"
        }
        rig.ideas.all().single().suggestedAt shouldBe null
        rig.feed.page(0, 20).items.shouldBeEmpty()
    }
    test("a post that reached the feed without a call key keeps its reservation (found by the idea's ref), also when cancelled after it") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        val real = rig.own.sendToUser(rig.leadScope)
        val cancelledLate = rig.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by real {
                override suspend fun invoke(input: JsonObject): ToolResult {
                    real.invoke(input)
                    throw kotlinx.coroutines.CancellationException("stopped after the post")
                }
            },
            rig.leadScope,
        )
        val cards = listOf(
            Card.Text("Thursday evening is free."),
            Card.Choices("Add it?", listOf(Choice("Add to calendar", "Add to calendar"), Choice("Not this time", "Not this time")), ref = "outing:$a"),
        )
        // no ToolCallContext at all: the post has no client key, only the card's ref
        runCatching { cancelledLate.invoke(buildJsonObject { put("kind", "look_forward"); put("cards", ClarityJson.encodeToJsonElement(cards)) }) }
            .exceptionOrNull().shouldBeInstanceOf<kotlinx.coroutines.CancellationException>()
        rig.feed.page(0, 20).items.filter { it.kind == MessageKind.LOOK_FORWARD } shouldHaveSize 1
        rig.ideas.all().single().suggestedAt shouldBe now
    }

    test("the per-turn offer slot is taken before sending: three at once make two, and a failed post gives its slot back") {
        val rig = Rig(database, now, settings)
        val ids = (1..4).map { rig.saveIdea("Outing $it") }
        val person = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        val results = kotlinx.coroutines.coroutineScope {
            val scope = this
            ids.take(3).map { id -> scope.async(kotlinx.coroutines.Dispatchers.IO) { rig.postVia(rig.sendAs(person), "outing:$id") } }.map { it.await() }
        }
        results.count { it is ToolResult.Ok } shouldBe 2
        person.offersThisTurn.get() shouldBe 2

        val person2 = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        val failing = rig.outing.gateSuggestions(
            object : net.robog.clarity.harness.Tool by rig.own.sendToUser(person2) {
                override suspend fun invoke(input: JsonObject): ToolResult = ToolResult.Error("could not post")
            },
            person2,
        )
        rig.postVia(failing, "outing:${ids[3]}").shouldBeInstanceOf<ToolResult.Error>().message shouldBe "could not post"
        person2.offersThisTurn.get() shouldBe 0
        rig.ideas.all().first { it.id == ids[3] }.suggestedAt shouldBe null
    }
    test("a reservation that throws gives the offer slot back") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        val broken = object : OutingStore by rig.ideas {
            override suspend fun reserve(id: String, at: Instant, call: String?, personTurn: Boolean, reshow: Boolean): Reservation = error("database down")
        }
        val person = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = true) }
        val gate = OutingTools(broken, rig.people, rig.reader, SourceResolver(rig.reader, { settings }, rig.journal, ExposedRunStore(database, rig.clock)), { settings }, rig.clock)
            .gateSuggestions(rig.own.sendToUser(person), person)
        runCatching { rig.postVia(gate, "outing:$a") }.exceptionOrNull()?.message shouldBe "database down"
        person.offersThisTurn.get() shouldBe 0
        rig.feed.page(0, 20).items.shouldBeEmpty()
    }
    test("the server still decides what is a reply: an outing post in a turn answering the person is one, in a job turn it is look_forward") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        val b = rig.saveIdea("Pottery class")
        val c = rig.saveIdea("Hike at the lake")
        rig.suggest(a).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b, asked = true, kind = "look_forward").shouldBeInstanceOf<ToolResult.Ok>()
        // a job turn the person interrupted is answering them (a reply), yet the outing gate still treats it as the lead's own idea
        val interrupted = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = false); it.userAsked() }
        rig.postVia(rig.sendAs(interrupted), "outing:$c").shouldBeInstanceOf<ToolResult.Ok>()
        val kinds = rig.feed.page(0, 20).items.map { it.kind }
        kinds shouldBe listOf(MessageKind.LOOK_FORWARD, MessageKind.REPLY, MessageKind.REPLY)
        // a model that claims reply outside such a turn gets a finding (a question with buttons is shown at once), whatever the cards
        val job = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = false) }
        rig.postVia(rig.sendAs(job), null, kind = "reply").shouldBeInstanceOf<ToolResult.Ok>()
        rig.feed.page(0, 20).items.last().kind shouldBe MessageKind.FINDING
    }
    test("a tap is not a request: a turn of only taps still has the weekly budget, a typed message in the batch lifts it") {
        val rig = Rig(database, now, settings)
        val ids = (1..3).map { rig.saveIdea("Outing $it") }
        rig.suggest(ids[0]).shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(ids[1]).shouldBeInstanceOf<ToolResult.Ok>() // the week's budget is used
        fun tap(seq: Long) = net.robog.clarity.backend.agent.PendingItem(
            seq, net.robog.clarity.backend.agent.InboxItem.UserInput(net.robog.clarity.shared.feed.ClientInputId("tap$seq"), "Later", tap = true), now,
        )
        val typed = net.robog.clarity.backend.agent.PendingItem(
            9, net.robog.clarity.backend.agent.InboxItem.UserInput(net.robog.clarity.shared.feed.ClientInputId("typed"), "what could I do tonight"), now,
        )
        // "Later" on a plan check-in, then the lead offers an outing in that turn: gated like a job turn
        val tapsOnly = RunScope("lead", isLead = true).also {
            val items = listOf(tap(1))
            it.startTurn(untrusted = false, requestedByUser = true, startedByPerson = net.robog.clarity.backend.agent.turnStartedByPerson(items))
        }
        tapsOnly.requestedByUser shouldBe true
        rig.postVia(rig.sendAs(tapsOnly), "outing:${ids[2]}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "2 outings"
        val typedToo = RunScope("lead", isLead = true).also {
            val items = listOf(tap(1), typed)
            it.startTurn(untrusted = false, requestedByUser = true, startedByPerson = net.robog.clarity.backend.agent.turnStartedByPerson(items))
        }
        rig.postVia(rig.sendAs(typedToo), "outing:${ids[2]}").shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("refs are checked when posting: unknown prefixes and malformed refs are refused, a plan ref must name an open plan item") {
        val rig = Rig(database, now, settings)
        val item = rig.planItems.plan(kotlinx.datetime.LocalDate(2026, 11, 16), listOf(net.robog.clarity.backend.sessions.Planned("Write the report"))).single()
        suspend fun post(ref: String?) = rig.postVia(rig.sendToUser, ref, kind = "check_in")
        post(null).shouldBeInstanceOf<ToolResult.Ok>()
        post("plan:${item.id}").shouldBeInstanceOf<ToolResult.Ok>()
        post("task:${item.id}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "neither"
        post("Plan:${item.id}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "neither"
        post("plan:${item.id.uppercase()}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "neither"
        post("plan:${Uuid.random()}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No open plan item"
        rig.planItems.update(item.id) { it.copy(state = net.robog.clarity.backend.sessions.PlanItemState.DONE) }
        post("plan:${item.id}").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No open plan item"
        rig.feed.page(0, 20).items shouldHaveSize 2
    }

    test("Coming up in the evening summary leaves out an outing whose idea was declined, and keeps an unanswered one") {
        val rig = Rig(database, now, settings)
        val a = rig.saveIdea("Jazz night")
        val b = rig.saveIdea("Pottery class")
        rig.suggest(a, text = "Offer jazz").shouldBeInstanceOf<ToolResult.Ok>()
        rig.suggest(b, text = "Offer pottery").shouldBeInstanceOf<ToolResult.Ok>()
        rig.call("update_outing_idea", "id" to a, "state" to "declined").shouldBeInstanceOf<ToolResult.Ok>()
        val job = net.robog.clarity.backend.scheduler.EveningBatchJob(rig.feedStore, rig.feed, ExposedTaskStore(database, rig.clock, rig.feed), rig.ideas)
        job.fire(FireContext(scheduledFor = now + 1.hours, now = now + 1.hours, lastRunAt = null, settings = settings))
        val summary = rig.feed.page(0, 20).items.last().cards.filterIsInstance<Card.Chips>().single { it.label == "Coming up" }
        summary.items.map { it.text } shouldBe listOf("Offer pottery")
    }
})
