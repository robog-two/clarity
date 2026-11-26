package net.robog.clarity.backend.promptlab

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.sessions.Playbooks
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.session.CallStage

private fun textOf(m: Message) = m.content.filterIsInstance<Block.Text>().single().text

class FixedTextSwapsTest : FunSpec({
    val fake = listOf(
        Swap("greeting", "test", SwapTarget.SYSTEM, Regex("""Hello (\w+)\.""")) { "Hi ${it.groupValues[1]}!" },
        Swap("same", "test", SwapTarget.SYSTEM, Regex("""unchanged""")) { it.value },
        Swap("skipped", "test", SwapTarget.SYSTEM, Regex("""leave me""")) { null },
        Swap("result", "test", SwapTarget.RESULT, Regex("""\ADelivered\.\z""")) { "Sent." },
        Swap("placeholder", "test", SwapTarget.USER, Regex("""\A\(update\)\z""")) { "(new update)" },
    )

    test("only exact matches are replaced, counted, and the others left alone") {
        val r = refreshMessages(
            listOf(
                Message.system("[inbox]\n- Hello Sam. unchanged. leave me. Hello, again."),
                Message.user("Hello Sam. (update) inside the person's words"),
                Message.user("(update)"),
                Message(Role.USER, listOf(Block.Result("t", "Delivered."), Block.Result("t", "Delivered. More"))),
            ),
            fake,
        )
        textOf(r.messages[0]) shouldBe "[inbox]\n- Hi Sam! unchanged. leave me. Hello, again."
        textOf(r.messages[1]) shouldBe "Hello Sam. (update) inside the person's words"
        textOf(r.messages[2]) shouldBe "(new update)"
        (r.messages[3].content[0] as Block.Result).content shouldBe "Sent."
        (r.messages[3].content[1] as Block.Result).content shouldBe "Delivered. More"
        r.matched shouldBe mapOf("greeting" to 1, "same" to 1, "placeholder" to 1, "result" to 1)
        r.changed shouldBe mapOf("greeting" to 1, "placeholder" to 1, "result" to 1)
        r.totalChanged shouldBe 3
    }

    test("a swap whose new text equals the old changes nothing") {
        val r = refreshMessages(listOf(Message.system("unchanged")), fake)
        r.totalChanged shouldBe 0
        r.changedChars shouldBe 0
    }

    test("a frozen session playbook becomes what the current code renders, and the session id stays") {
        val old = FixedTextOld.CHOOSE.first()
        val id = "c3fb5cff-9809-4ac8-a0a8-ee1bd9b6bc4e"
        val r = refreshMessages(listOf(Message.system("[inbox, now x]\n- Session $id: $old\n- another")), productionSwaps(TimeZone.UTC))
        textOf(r.messages.single()) shouldBe "[inbox, now x]\n- Session $id: ${Playbooks.morningCall(CallStage.CHOOSE)}\n- another"
    }

    test("a frozen nudge instruction is swapped and the lead's own note stays") {
        val note = "Ask whether the laundry is in."
        val old = "Nudge you set at 2026-11-20T14:55-05:00 (Fri): $note${FixedTextOld.NUDGE_TAIL.last()}"
        val r = refreshMessages(listOf(Message.system("[inbox, now x]\n- $old")), productionSwaps(TimeZone.UTC))
        textOf(r.messages.single()).contains("Nudge you set at 2026-11-20T14:55-05:00 (Fri): $note ") shouldBe true
    }
})
