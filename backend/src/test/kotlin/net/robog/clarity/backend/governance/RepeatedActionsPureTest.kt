package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

private fun send(key: String = "r:1", body: String = "Hi Dana", to: List<Handle> = listOf(Handle.email("dana@x.org")), subject: String = "Hello") =
    ProposedAction.SendEmail(key, "Email Dana", EmailDraft(from = Handle.email("me@x.org"), to = to, subject = subject, body = body))

/** What makes two outbound actions the same one. */
class RepeatedActionsPureTest : FunSpec({
    // Guards against: a repeated email with a new call id (and so a new key) counting as new.
    test("the key and summary do not matter; recipients' case and order and whitespace do not either") {
        val a = send(key = "r:1")
        actionFingerprint(a) shouldBe actionFingerprint(send(key = "r:2").copy(summary = "other"))
        actionFingerprint(send(to = listOf(Handle.email("a@x.org"), Handle.email("b@x.org")))) shouldBe
            actionFingerprint(send(to = listOf(Handle.email("B@X.org "), Handle.email("a@x.org"))))
        actionFingerprint(a) shouldBe actionFingerprint(send(body = "  Hi\n  Dana "))
        actionFingerprint(a) shouldBe actionFingerprint(send(subject = "hello"))
    }

    // Guards against: an edited email being treated as a repeat and dropped.
    test("another body, subject, recipient, cc, thread or kind is a different action") {
        val a = actionFingerprint(send())
        a shouldNotBe actionFingerprint(send(body = "Hi Dana!!"))
        a shouldNotBe actionFingerprint(send(subject = "Hello again"))
        a shouldNotBe actionFingerprint(send(to = listOf(Handle.email("eve@x.org"))))
        a shouldNotBe actionFingerprint(send().let { it.copy(email = it.email.copy(cc = listOf(Handle.email("bo@x.org")))) })
        a shouldNotBe actionFingerprint(send().let { it.copy(email = it.email.copy(inReplyTo = EntityId("m1"))) })
        a shouldNotBe actionFingerprint(ProposedAction.SaveDraft("r:1", "Draft", send().email))
    }

    test("events and reminders compare by their times; reads and external tools are never deduplicated") {
        val t = Instant.parse("2026-11-02T09:00:00Z")
        val r = ProposedAction.CreateReminder("r:1", "x", "Call the dentist", t)
        actionFingerprint(r) shouldBe actionFingerprint(r.copy(idempotencyKey = "r:2", text = "call the  dentist"))
        actionFingerprint(r) shouldNotBe actionFingerprint(r.copy(at = Instant.parse("2026-11-02T10:00:00Z")))
        actionFingerprint(ProposedAction.ReadDocument("r:1", "x", EntityId("d"))).shouldBeNull()
        actionFingerprint(ProposedAction.ExternalTool("r:1", "x", "s", "t", JsonObject(emptyMap()))).shouldBeNull()
    }

    test("the run prefix of a key, and a LIKE pattern for it") {
        runKeyPrefix("thread-1:toolu_9") shouldBe "thread-1:"
        runKeyPrefix("no-colon").shouldBeNull()
        runKeyPrefix(":x").shouldBeNull()
        likePrefix("run_1:") shouldBe "run\\_1:%"
        likePrefix("a%b\\") shouldBe "a\\%b\\\\%"
    }

    // Guards against: a different repeat rule or place counting as the same event.
    test("event edits and cancellations compare by what they change and how far") {
        val hold = ProposedAction.CreateEvent("k", "s", null, "Gym", Instant.parse("2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"))
        val weekly = hold.copy(repeat = net.robog.clarity.shared.calendar.Recurrence(net.robog.clarity.shared.calendar.Frequency.WEEKLY))
        actionFingerprint(hold) shouldNotBe actionFingerprint(weekly)
        actionFingerprint(hold) shouldNotBe actionFingerprint(hold.copy(location = "Pool"))
        actionFingerprint(hold) shouldBe actionFingerprint(hold.copy(idempotencyKey = "other", summary = "other"))

        val scope = net.robog.clarity.shared.calendar.EventScope.THIS
        val update = ProposedAction.UpdateEvent("k", "s", EntityId("e1"), scope, title = "Gym B")
        actionFingerprint(update) shouldBe actionFingerprint(update.copy(idempotencyKey = "k2", summary = "x"))
        actionFingerprint(update) shouldNotBe actionFingerprint(update.copy(scope = net.robog.clarity.shared.calendar.EventScope.SERIES))
        actionFingerprint(update) shouldNotBe actionFingerprint(update.copy(title = "Gym C"))
        actionFingerprint(update) shouldNotBe actionFingerprint(update.copy(event = EntityId("e2")))

        val cancel = ProposedAction.CancelEvent("k", "s", EntityId("e1"), scope)
        actionFingerprint(cancel) shouldNotBe actionFingerprint(cancel.copy(scope = net.robog.clarity.shared.calendar.EventScope.FOLLOWING))
        actionFingerprint(cancel) shouldNotBe actionFingerprint(update.copy(title = null))
    }
})
