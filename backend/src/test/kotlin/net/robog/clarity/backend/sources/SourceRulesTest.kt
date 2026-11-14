package net.robog.clarity.backend.sources

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.citationsBlock
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ClarityStrictJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceKind
import net.robog.clarity.shared.source.SourceView

private const val ID_A = "8f0c1d2e-0000-4000-8000-00000000000a"
private const val ID_B = "8f0c1d2e-0000-4000-8000-00000000000b"
private val newYork = TimeZone.of("America/New_York")
private val me = Handle.email("me@example.com")

private fun call(id: String, name: String) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, name, buildJsonObject { put("q", "x") })))
private fun result(id: String, content: String, error: Boolean = false) = Message(Role.USER, listOf(Block.Result(id, content, error)))

class SourceRulesTest : FunSpec({
    context("what a run read") {
        test("an id in a tool result counts") {
            val reads = readsOf(listOf(call("t1", "list_messages"), result("t1", """[{"id":"$ID_A","subject":"Hi"}]""")))
            idRead(ID_A, reads) shouldBe true
            idRead(ID_B, reads) shouldBe false
        }

        test("an id only in the model's own text, a tool call or the person's message does not count") {
            val messages = listOf(
                Message(Role.USER, listOf(Block.Text("please look at $ID_A"))),
                Message(Role.ASSISTANT, listOf(Block.Text("I think it was $ID_A"), Block.ToolUse("t1", "read_memory", buildJsonObject { put("id", ID_A) }))),
                result("t1", "(nothing)"),
            )
            idRead(ID_A, readsOf(messages)) shouldBe false
        }

        test("a failed tool result does not count") {
            idRead(ID_A, readsOf(listOf(call("t1", "list_messages"), result("t1", "no such $ID_A", error = true)))) shouldBe false
        }

        test("an id inside a longer id or word does not count") {
            idRead(ID_A, readsOf(listOf(call("t1", "list_messages"), result("t1", "x${ID_A}0 ${ID_A}-2 a$ID_A")))) shouldBe false
            val reads = readsOf(listOf(call("t1", "list_messages"), result("t1", """[{"id":"${ID_A.uppercase()}"}] and see $ID_B.""")))
            idRead(ID_A, reads) shouldBe true
            idRead(ID_B, reads) shouldBe true
        }

        test("a short token never matches") {
            idRead("12", readsOf(listOf(call("t1", "list_messages"), result("t1", "id 12 and 123")))) shouldBe false
            idTokens("id 12 and $ID_A") shouldBe setOf(ID_A)
        }

        test("only a record tool's result counts, not an echo or a helper's prose") {
            val messages = listOf(
                call("t1", "create_trigger"), result("t1", """{"created":true,"note":"about $ID_A"}"""),
                call("t2", "delegate"), result("t2", "The helper found $ID_B in the mail."),
                call("t3", "check_helper"), result("t3", "done, see $ID_B"),
            )
            val reads = readsOf(messages)
            idRead(ID_A, reads) shouldBe false
            idRead(ID_B, reads) shouldBe false
            for (tool in listOf("list_messages", "search_messages", "list_calendar_events", "search_contacts", "search_documents", "read_document")) {
                idRead(ID_A, readsOf(listOf(call("t", tool), result("t", "id $ID_A")))) shouldBe true
            }
        }

        test("reads of several runs add up") {
            val lead = readsOf(listOf(call("t1", "list_messages"), result("t1", "id $ID_A")))
            val helper = readsOf(listOf(call("t2", "search_messages"), result("t2", "id $ID_B")))
            val both = lead + helper
            idRead(ID_A, both) shouldBe true
            idRead(ID_B, both) shouldBe true
            idRead(ID_B, lead) shouldBe false
        }
    }

    context("web pages") {
        val fetched = """
            Title: Garden Guide
            URL: https://example.com/guide
            Fetched: 2026-10-26T10:00:00Z

            Plant in spring.
        """.trimIndent()
        val fetchMessages = listOf(call("f1", "sandbox__fetch_page"), result("f1", fetched))

        test("a fetched page counts, with its title, and the address is compared loosely") {
            val reads = readsOf(fetchMessages)
            pageRead("https://example.com/guide", reads)?.title shouldBe "Garden Guide"
            pageRead("HTTPS://Example.com/guide/", reads)?.title shouldBe "Garden Guide"
            pageRead("https://example.com/guide#top", reads)?.title shouldBe "Garden Guide"
            pageRead("https://example.com/other", reads) shouldBe null
            pageRead("https://evil.example.org/guide", reads) shouldBe null
        }

        test("a search citation counts") {
            val messages = listOf(Message(Role.ASSISTANT, listOf(citationsBlock(listOf(UrlCitation("https://example.com/c", "Cited"))))))
            pageRead("https://example.com/c", readsOf(messages))?.title shouldBe "Cited"
        }

        test("an address only the model wrote does not count") {
            val messages = listOf(Message(Role.ASSISTANT, listOf(Block.Text("see https://example.com/guide"))))
            pageRead("https://example.com/guide", readsOf(messages)) shouldBe null
        }

        test("a failed fetch does not count") {
            pageRead("https://example.com/guide", readsOf(listOf(call("f1", "sandbox__fetch_page"), result("f1", fetched, error = true)))) shouldBe null
        }
    }

    context("the items asked for") {
        test("repeats are dropped and the order kept") {
            val items = listOf(SourceItem.Entity(ID_A), SourceItem.Web("https://example.com/a"), SourceItem.Entity(ID_A.uppercase()), SourceItem.Web("https://EXAMPLE.com/a/"))
            requestedSources(items).getOrNull()!!.map { it.label() } shouldContainExactly listOf(ID_A, "https://example.com/a")
        }

        test("an empty card, too many items and bad addresses are errors") {
            requestedSources(emptyList()).leftOrNull() shouldContain "at least one"
            requestedSources((1..9).map { SourceItem.Entity("record-number-$it") }).leftOrNull() shouldContain "at most 8"
            requestedSources((1..8).map { SourceItem.Entity("record-number-$it") }).isRight() shouldBe true
            requestedSources(listOf(SourceItem.Web("javascript:alert(1)"))).leftOrNull() shouldContain "javascript:alert(1)"
            requestedSources(listOf(SourceItem.Web("https://exa mple.com/"))).isLeft() shouldBe true
            requestedSources(listOf(SourceItem.Entity("  "))).isLeft() shouldBe true
        }

        test("the title the model gave is never kept") {
            val clean = requestedSources(listOf(SourceItem.Entity(ID_A, SourceKind.EVENT, "Trust me", "Totally"))).getOrNull()!!.single()
            clean shouldBe SourceItem.Entity(ID_A)
        }

        test("the unread ones are named in one error") {
            val reads = readsOf(listOf(call("t1", "list_messages"), result("t1", "id $ID_A")))
            val unread = unreadSources(listOf(SourceItem.Entity(ID_A), SourceItem.Entity(ID_B), SourceItem.Web("https://example.com/x")), reads)
            unread.map { it.label() } shouldContainExactly listOf(ID_B, "https://example.com/x")
            val text = refusal(unread)
            text shouldContain ID_B
            text shouldContain "later step"
            text shouldContain "https://example.com/x"
            text shouldNotContain ID_A
        }

        test("a missing record is named too, and a long or multi-line address is cut") {
            refusal(emptyList(), listOf(SourceItem.Entity(ID_A))) shouldBe "No such record: $ID_A."
            (refusal(listOf(SourceItem.Web("https://example.com/" + "a".repeat(500) + "\n\u202Eb"))).length < 400) shouldBe true
            refusal(listOf(SourceItem.Web("https://example.com/a\nb"))) shouldNotContain "\n"
        }
    }

    context("items from stored records") {
        val message = MessagePayload(
            MessagePayload.Channel.EMAIL, Handle.email("me@example.com"), Handle.email("sam@example.com", "Sam Example"), listOf(Handle.email("me@example.com")),
            subject = "Lunch‮ on\nFriday", body = "Noon at the cafe.", sentAt = Instant.parse("2026-10-26T01:00:00Z"),
            attachments = listOf(AttachmentMeta("2", "menu.pdf", "application/pdf", 1200), AttachmentMeta("3", "logo.png", "image/png", 10, inline = true)),
        )

        test("a message: subject, sender and date on the person's clock, files that are not inline") {
            val item = entityItem(ID_A, message, newYork)!!
            item.kind shouldBe SourceKind.MESSAGE
            item.title shouldBe "Lunch on Friday"
            item.sub shouldBe "Sam Example · Sun Oct 25, 9:00 pm"
            item.by shouldBe "Sam Example"
            item.at shouldBe message.sentAt
            item.attachments shouldBe 1
        }

        test("a message without a subject has one title, on the card and in the viewer") {
            entityItem(ID_A, message.copy(subject = null), newYork)!!.title shouldBe "(no subject)"
            entityItem(ID_A, message.copy(subject = " "), newYork)!!.title shouldBe "(no subject)"
            sourceView(ID_A, message.copy(subject = null)).title shouldBe "(no subject)"
        }

        test("an event, a document and a contact") {
            val event = CalendarEventPayload(calendar = "Home", title = "Dentist", start = Instant.parse("2026-10-27T14:00:00Z"), end = Instant.parse("2026-10-27T15:00:00Z"))
            entityItem(ID_A, event, newYork)!!.apply { kind shouldBe SourceKind.EVENT; sub shouldBe "Tue Oct 27, 10:00 am"; at shouldBe event.start; day shouldBe null }
            val allDay = event.copy(allDay = true, start = Instant.parse("2026-10-27T00:00:00Z"), end = Instant.parse("2026-10-28T00:00:00Z"))
            entityItem(ID_A, allDay, newYork)!!.apply { sub shouldBe "Tue Oct 27"; at shouldBe null; day shouldBe LocalDate(2026, 10, 27) }
            entityItem(ID_A, DocumentPayload("webdav:x", "/Taxes/2025.pdf", "Taxes 2025"), newYork)!!.apply { kind shouldBe SourceKind.DOCUMENT; title shouldBe "Taxes 2025"; sub shouldBe "/Taxes/2025.pdf" }
            entityItem(ID_A, ContactPayload("Sam Example", listOf(Handle.email("sam@example.com")), "Example Co"), newYork)!!.apply { kind shouldBe SourceKind.CONTACT; sub shouldBe "Example Co" }
        }

        test("a long title is cut to one line") {
            entityItem(ID_A, message.copy(subject = "word ".repeat(100)), newYork)!!.title.length shouldBe 120
        }

        test("a page shows its host as punycode and its fetched title") {
            val web = webItem("https://аpple.example/path", "  Apple\nPage ")
            web.host shouldBe "xn--pple-43d.example"
            web.title shouldBe "Apple Page"
            webItem("https://example.com/x", null).title shouldBe "example.com"
        }

        test("the card's plain text lists the titles") {
            val card = sourcesCard(listOf(SourceItem.Entity(ID_A, title = "Lunch"), SourceItem.Web("https://example.com", "Guide", "example.com")))
            card.fallbackText shouldBe "Sources. Lunch. Guide."
            sourcesCard(listOf(SourceItem.Entity(ID_A, title = ""), SourceItem.Web("https://example.com", "Guide", "example.com"))).fallbackText shouldBe "Sources. Guide."
            sourcesCard(listOf(SourceItem.Entity(ID_A))).fallbackText shouldBe "Sources."
        }
    }

    context("the source view") {
        test("a message with its header, body and files") {
            val view = sourceView(ID_A, MessagePayload(
                MessagePayload.Channel.EMAIL, Handle.email("me@example.com"), Handle.email("sam@example.com", "Sam‮ Example"), listOf(Handle.email("me@example.com"), Handle.phone("+15550100")),
                subject = "Lunch", body = "Line one\nLine two", sentAt = Instant.parse("2026-10-26T01:00:00Z"),
                attachments = listOf(AttachmentMeta("2", "menu\n.pdf", "application/pdf", 1200), AttachmentMeta("3", null, "image/png", null, inline = true)),
            )).shouldBeInstanceOf<SourceView.Message>()
            view.title shouldBe "Lunch"
            view.channel shouldBe "email"
            view.from shouldBe "Sam Example <sam@example.com>"
            view.to shouldContainExactly listOf("me@example.com", "+15550100")
            view.body shouldBe "Line one\nLine two"
            view.attachments.map { it.part to it.filename } shouldContainExactly listOf("2" to "menu .pdf")
        }

        test("an event, a document and a contact") {
            val event = sourceView(ID_A, CalendarEventPayload(
                calendar = "Home", title = "Dentist", start = Instant.parse("2026-10-27T14:00:00Z"), end = Instant.parse("2026-10-27T15:00:00Z"),
                location = "12 Main St", description = "Bring forms", url = "https://example.com/e", repeats = "every Tue", attendees = listOf(Handle.email("sam@example.com")),
            )).shouldBeInstanceOf<SourceView.Event>()
            event.location shouldBe "12 Main St"
            event.link shouldBe "https://example.com/e"
            event.repeats shouldBe "every Tue"
            event.attendees shouldBe listOf("sam@example.com")
            sourceView(ID_A, DocumentPayload("webdav:x", "/a/b.txt", "", size = 5, excerpt = "hello")).shouldBeInstanceOf<SourceView.Document>().apply { title shouldBe "/a/b.txt"; excerpt shouldBe "hello" }
            sourceView(ID_A, ContactPayload("Sam Example", listOf(Handle.phone("+15550100")), notes = "Met at lunch")).shouldBeInstanceOf<SourceView.Contact>().apply { handles shouldBe listOf("+15550100"); notes shouldBe "Met at lunch" }
        }

        test("long text is cut with a flag and reordering characters are stripped") {
            val long = MessagePayload(MessagePayload.Channel.EMAIL, me, me, listOf(me), "S", "a\u202Eb".repeat(40_000), Instant.parse("2026-10-26T01:00:00Z"))
            val view = sourceView(ID_A, long).shouldBeInstanceOf<SourceView.Message>()
            view.truncated shouldBe true
            view.body.length shouldBe MAX_VIEW_TEXT
            view.body shouldNotContain "\u202E"
            sourceView(ID_A, long.copy(body = "short\u202Etext")).shouldBeInstanceOf<SourceView.Message>().apply { body shouldBe "shorttext"; truncated shouldBe false }
            cappedText("x".repeat(9) + "\uD83D\uDE00", 10).first.length shouldBe 9
            val doc = sourceView(ID_A, DocumentPayload("s", "/p", "T", excerpt = "e".repeat(60_000))).shouldBeInstanceOf<SourceView.Document>()
            doc.truncated shouldBe true
        }

        test("it round-trips on the wire") {
            val view: SourceView = SourceView.Document(ID_A, "T", "/p", "text/plain", 3, null, "x")
            val text = ClarityJson.encodeToString(SourceView.serializer(), view)
            text shouldContain "\"type\":\"document\""
            ClarityJson.decodeFromString(SourceView.serializer(), text) shouldBe view
        }
    }

    context("the card on the wire") {
        test("the model's items are only an id or a url, told apart by their keys") {
            val card = ClarityStrictJson.decodeFromString(Card.serializer(), """{"type":"sources","items":[{"id":"$ID_A"},{"url":"https://example.com/x"}]}""")
            card shouldBe Card.Sources(listOf(SourceItem.Entity(ID_A), SourceItem.Web("https://example.com/x")))
        }

        test("a resolved card round-trips and an older app reads its fallback text") {
            val card: Card = sourcesCard(listOf(SourceItem.Entity(ID_A, SourceKind.EVENT, "Dentist", "Tue", 0), SourceItem.Web("https://example.com/x", "Guide", "example.com")))
            val text = ClarityJson.encodeToString(Card.serializer(), card)
            ClarityJson.decodeFromString(Card.serializer(), text) shouldBe card
            ClarityJson.decodeFromString(Card.serializer(), text.replace("\"type\":\"sources\"", "\"type\":\"sources_v2\"")) shouldBe Card.Unknown("Sources. Dentist. Guide.")
        }

        test("an item with neither key decodes as unknown, so a feed never breaks, and the tool refuses it") {
            val card = ClarityStrictJson.decodeFromString(Card.serializer(), """{"type":"sources","items":[{"title":"x"},{"id":"$ID_A"}]}""") as Card.Sources
            card.items shouldBe listOf(SourceItem.Unknown, SourceItem.Entity(ID_A))
            requestedSources(card.items).leftOrNull() shouldContain "\"id\""
        }

        test("a feed with an unknown kind and an odd item still decodes") {
            val json = """{"id":"f1","seq":3,"at":"2026-10-28T10:00:00Z","author":"agent","cards":[""" +
                """{"type":"text","text":"Here."},""" +
                """{"type":"sources","items":[{"id":"$ID_A","kind":"voicemail","title":"Call"},{"nonsense":true},{"url":"https://example.com/x","title":"T","host":"example.com"}],"fallbackText":"Sources. Call."}]}"""
            val item = ClarityJson.decodeFromString(net.robog.clarity.shared.feed.FeedItem.serializer(), json)
            val sources = item.cards[1] as Card.Sources
            sources.items shouldBe listOf(SourceItem.Entity(ID_A, SourceKind.UNKNOWN, "Call"), SourceItem.Unknown, SourceItem.Web("https://example.com/x", "T", "example.com"))
        }

        test("a new field on an item is read, an old one without it too") {
            val item = ClarityJson.decodeFromString(SourceItem.serializer(), """{"id":"$ID_A","kind":"event","title":"D","day":"2026-10-27","at":"2026-10-27T14:00:00Z"}""") as SourceItem.Entity
            item.day shouldBe LocalDate(2026, 10, 27)
            item.at shouldBe Instant.parse("2026-10-27T14:00:00Z")
        }
    }
})
