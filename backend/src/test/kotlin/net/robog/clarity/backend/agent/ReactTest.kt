package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.ItemReaction
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.feed.Reaction
import net.robog.clarity.shared.feed.latestReactions
import net.robog.clarity.shared.session.SessionId
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

class ReactPureTest : FunSpec({
    test("an unknown reaction is refused with the allowed names; known ones and a missing one pass") {
        reactionError(JsonPrimitive("smile")) shouldBe "Unknown reaction \"smile\": use one of thumbs_up, heart, laugh, thumbs_down."
        Reaction.entries.forEach { reactionError(JsonPrimitive(it.wire)).shouldBeNull() }
        reactionError(null).shouldBeNull()
    }

    test("a reaction is an answer to the person, so the lead is not nudged after one") {
        ANSWERING_TOOLS shouldContain "react"
    }

    test("the guidance: react only when the message clearly needs no reply; the nudge never pushes toward reacting") {
        val lead = AgentPrompts.LEAD_SYSTEM
        lead shouldContain "Only when their message clearly needs no reply (a plain acknowledgement, thanks, \"ok\", \"done\") may you answer with react instead"
        lead shouldContain "anything with a question, a request, new information or something to confirm gets a written reply"
        REPLY_NUDGE shouldNotContain "react"
    }

    test("the activity line names the reaction") {
        describeCall("react", buildJsonObject { put("reaction", "heart") }, "Reacted") shouldBe ("Reacted to your message" to "❤️")
    }

    test("the newest reaction on a message wins, and an unknown newest one shows none") {
        fun item(seq: Long, reaction: ItemReaction?) = FeedItem(
            net.robog.clarity.shared.feed.FeedItemId("i$seq"), seq, kotlin.time.Instant.parse("2026-10-29T10:00:00Z"), FeedItem.Author.AGENT,
            cards = emptyList(), reaction = reaction,
        )
        val a = net.robog.clarity.shared.feed.FeedItemId("a")
        val b = net.robog.clarity.shared.feed.FeedItemId("b")
        latestReactions(
            listOf(
                item(5, ItemReaction(a, "heart")), item(3, ItemReaction(a, "thumbs_up")),
                item(4, ItemReaction(b, "laugh")), item(6, ItemReaction(b, "party")), item(7, null),
            ),
        ) shouldBe mapOf(a to Reaction.HEART)
    }
})

private fun lastResultFor(request: ModelRequest, id: String) =
    request.messages.last().content.any { it is Block.Result && it.toolUseId == id }

private fun isNudge(request: ModelRequest) =
    request.messages.last().let { m -> m.role == Role.SYSTEM && m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text } == REPLY_NUDGE }

/** `react` against real Postgres: validation, its default target, replacing, how it reaches a phone, and the lead's turn. */
@EnabledIf(ContainerRuntimeAvailable::class)
class ReactDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun rig(): LeadRig = LeadRig(database, kotlin.time.Clock.System, "sk-test", TEST_ROLES).also { it.reset() }

    suspend fun LeadRig.said(text: String, session: SessionId? = null): FeedItem =
        feed.append(FeedDraft(FeedItem.Author.USER, listOf(Card.Said(text)), sessionId = session, clientInputId = ClientInputId("c-$text"))).item

    suspend fun LeadRig.agentSays(text: String): FeedItem = feed.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text(text)))).item

    fun input(reaction: String, messageId: String? = null) = buildJsonObject {
        put("reaction", reaction)
        messageId?.let { put("messageId", it) }
    }

    test("an unknown reaction, a missing message and a message that is not the person's are refused; nothing is posted") {
        val r = rig()
        val mine = r.said("ok thanks")
        val theirs = r.agentSays("Moved it to 4pm.")
        val react = r.own.react()

        (react.invoke(input("smile")) as ToolResult.Error).message shouldContain "thumbs_up, heart, laugh, thumbs_down"
        (react.invoke(input("heart", "00000000-0000-0000-0000-000000000000")) as ToolResult.Error).message shouldContain "No message with id"
        (react.invoke(input("heart", "not-a-uuid")) as ToolResult.Error).message shouldContain "No message with id"
        (react.invoke(input("heart", theirs.id.value)) as ToolResult.Error).message shouldContain "not the person's"

        r.feed.page(0, 50).items.map { it.id } shouldBe listOf(mine.id, theirs.id)
    }

    test("with nobody's words to react to it says so") {
        val r = rig()
        r.agentSays("Morning.")
        (r.own.react().invoke(input("thumbs_up")) as ToolResult.Error).message shouldContain "has not written anything"
    }

    test("by default it reacts to the person's latest message, as a quiet item of its own with no cards, in the target's session") {
        val r = rig()
        r.said("move my 3pm")
        r.agentSays("Moved it to 4pm.")
        val session = SessionId("s1")
        val latest = r.said("perfect, thanks", session)
        r.agentSays("Anything else?")

        val result = r.own.react().invoke(input("thumbs_up"))
        result.shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "Reacted 👍 to “perfect, thanks”"

        val reaction = r.feed.page(0, 50).items.last()
        reaction.reaction shouldBe ItemReaction(latest.id, "thumbs_up")
        reaction.cards.shouldBeEmpty()
        reaction.author shouldBe FeedItem.Author.AGENT
        reaction.notify shouldBe NOTIFY_IN_APP
        reaction.sessionId shouldBe session
        // A reaction or a topic marker is never "the latest message" itself.
        r.feed.latestMessage(FeedItem.Author.USER)?.id shouldBe latest.id
        r.feed.latestMessage(FeedItem.Author.AGENT)?.cards shouldBe listOf(Card.Text("Anything else?"))
    }

    test("a later reaction on the same message replaces the earlier one; an earlier message can be named by id") {
        val r = rig()
        val first = r.said("I'll be late")
        val second = r.said("done!")
        val react = r.own.react()
        react.invoke(input("thumbs_up")).shouldBeInstanceOf<ToolResult.Ok>()
        react.invoke(input("heart")).shouldBeInstanceOf<ToolResult.Ok>()
        react.invoke(input("thumbs_down", first.id.value)).shouldBeInstanceOf<ToolResult.Ok>()

        latestReactions(r.feed.page(0, 50).items) shouldBe mapOf(second.id to Reaction.HEART, first.id to Reaction.THUMBS_DOWN)
    }

    test("it reaches a phone that already has the message (a new seq, announced live) and a fresh full resync") {
        val r = rig()
        val target = r.said("sounds good")
        val phoneSeq = r.feed.page(0, 50).items.maxOf { it.seq }
        val published = CopyOnWriteArrayList<FeedEvent>()
        coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { r.hub.events.collect { published += it } }
            r.own.react().invoke(input("laugh")).shouldBeInstanceOf<ToolResult.Ok>()
            kotlinx.coroutines.yield()
            collector.cancelAndJoin()
        }
        val live = published.filterIsInstance<FeedEvent.ItemAppended>().single().item
        live.reaction shouldBe ItemReaction(target.id, "laugh")

        // The phone's catch-up after its last seq.
        val after = r.feed.page(phoneSeq, 50).items
        after shouldHaveSize 1
        after.single().seq shouldBe live.seq
        latestReactions(after) shouldBe mapOf(target.id to Reaction.LAUGH)
        // A phone starting from nothing.
        val all = r.feed.page(0, 50).items
        all.map { it.id } shouldBe listOf(target.id, live.id)
        latestReactions(all) shouldBe mapOf(target.id to Reaction.LAUGH)
    }

    test("the lead answers \"thanks\" with a reaction: the turn counts as answered, no reply nudge, no message") {
        val r = rig()
        val target = r.said("thanks!")
        r.model.handler = { request ->
            if (lastResultFor(request, "toolu_react")) end("") else callTool("toolu_react", "react", buildJsonObject { put("reaction", "heart") })
        }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("c-thanks!"), "thanks!"))
        r.lead.start()
        r.lead.step() shouldBe true

        r.model.requests shouldHaveSize 2
        r.model.requests.count(::isNudge) shouldBe 0
        val page = r.feed.page(0, 50).items
        page shouldHaveSize 2
        page.last().reaction shouldBe ItemReaction(target.id, "heart")
        page.last().cards.shouldBeEmpty()
        r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name } shouldContain "react"
    }
})
