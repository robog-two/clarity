package net.robog.clarity.backend.promptlab

import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import net.robog.clarity.backend.agent.COLLECTION_INDEX_HEADER
import net.robog.clarity.backend.agent.CriterionCheck
import net.robog.clarity.backend.agent.Decisions
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.JournalEntry
import net.robog.clarity.backend.agent.PendingItem
import net.robog.clarity.backend.agent.REPLY_NUDGE
import net.robog.clarity.backend.agent.Verdict
import net.robog.clarity.backend.agent.describe
import net.robog.clarity.backend.agent.renderDeliveries
import net.robog.clarity.backend.agent.renderMemoryContext
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.governance.NotifyDecision
import net.robog.clarity.backend.mcpservers.HostServer
import net.robog.clarity.backend.mcpservers.HostTool
import net.robog.clarity.backend.mcpservers.McpServer
import net.robog.clarity.backend.mcpservers.McpToolView
import net.robog.clarity.backend.mcpservers.renderMcpIndex
import net.robog.clarity.backend.outings.RecentContact
import net.robog.clarity.backend.outings.outingInstruction
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.PeriodicReviewJob
import net.robog.clarity.backend.scheduler.ReviewGate
import net.robog.clarity.backend.scheduler.ExposedReviewChanges
import net.robog.clarity.backend.sessions.DayPlan
import net.robog.clarity.backend.sessions.Playbooks
import net.robog.clarity.backend.sessions.prepInstruction
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.OPERATOR_UPDATE_PLACEHOLDER
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database

/*
 * The fixed text refresh. Server-injected fixed text (session playbooks, inbox notices, job instructions, tool results that
 * tell the model how to behave) is frozen inside the stored journal messages, so an edit to it in the code never reaches a
 * replay. Each Swap pairs the exact old text (frozen in FixedTextOld, or the old template written out here as a pattern)
 * with a render that calls the CURRENT production code with the parameters found in the old text. Only exact matches are
 * replaced; the person's words and data inside notices are never touched.
 */

/** Which parts of a message a swap may touch. The person's own messages (USER) only by an anchored whole-text match. */
enum class SwapTarget { SYSTEM, USER, RESULT }

/** [source] says where the current text comes from ("file:function"). [render] returns null to leave a match alone. */
class Swap(val name: String, val source: String, val target: SwapTarget, val old: Regex, val render: (MatchResult) -> String?)

data class Refreshed(
    val messages: List<Message>,
    /** Matches per swap name. */
    val matched: Map<String, Int>,
    /** Matches whose text differs from the frozen one, per swap name. */
    val changed: Map<String, Int>,
    /** Characters of the old and new text of the changed matches. */
    val changedChars: Int,
) {
    val totalMatched: Int get() = matched.values.sum()
    val totalChanged: Int get() = changed.values.sum()
}

/** Pure: [text] with every exact match of the [target] swaps replaced by its render. */
fun refreshText(text: String, swaps: List<Swap>, target: SwapTarget, matched: MutableMap<String, Int>, changed: MutableMap<String, Int>, chars: IntArray): String {
    var out = text
    for (swap in swaps) {
        if (swap.target != target) continue
        out = swap.old.replace(out) { m ->
            val new = swap.render(m) ?: return@replace m.value
            matched.merge(swap.name, 1, Int::plus)
            if (new != m.value) {
                changed.merge(swap.name, 1, Int::plus)
                chars[0] += m.value.length + new.length
            }
            new
        }
    }
    return out
}

/** Pure: [messages] with the fixed text refreshed. The assistant's turns are never touched. */
fun refreshMessages(messages: List<Message>, swaps: List<Swap>): Refreshed {
    val matched = linkedMapOf<String, Int>()
    val changed = linkedMapOf<String, Int>()
    val chars = IntArray(1)
    val out = messages.map { message ->
        val textTarget = when (message.role) {
            Role.SYSTEM -> SwapTarget.SYSTEM
            Role.USER -> SwapTarget.USER
            else -> return@map message
        }
        message.copy(
            content = message.content.map { block ->
                when (block) {
                    is Block.Text -> block.copy(text = refreshText(block.text, swaps, textTarget, matched, changed, chars))
                    is Block.Result -> block.copy(content = refreshText(block.content, swaps, SwapTarget.RESULT, matched, changed, chars))
                    else -> block
                }
            },
        )
    }
    return Refreshed(out, matched, changed, chars[0])
}

private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

private fun alternatives(olds: List<String>) = olds.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

private fun whole(old: String) = Regex("\\A" + Regex.escape(old) + "\\z")

private fun literal(name: String, source: String, olds: List<String>, new: () -> String, target: SwapTarget = SwapTarget.SYSTEM) =
    Swap(name, source, target, Regex(alternatives(olds))) { new() }

/** The text the current periodic review job asks after "Review what changed since <time>:", from the job itself. */
private fun currentReviewTail(): String {
    val captured = mutableListOf<InboxItem>()
    val inbox = object : InboxStore {
        override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean = captured.add(item)
        override suspend fun pending(limit: Int) = emptyList<PendingItem>()
        override suspend fun markConsumed(seqs: Collection<Long>) = Unit
    }
    val config = AgentConfig(reviewInterval = 2.hours)
    // The gate is off (no decision model), so the database is never read.
    val database = Database.connect("jdbc:postgresql://127.0.0.1:9/none", driver = "org.postgresql.Driver")
    val gate = ReviewGate(config, ExposedReviewChanges(database), Decisions(config, { null }, database, Clock.System))
    val slot = Instant.parse("2026-01-02T10:00:00Z")
    runBlocking { PeriodicReviewJob(config, inbox, gate).fire(FireContext(slot, slot + 1.hours, lastRunAt = slot - 2.hours, settings = Settings())) }
    val instruction = (captured.single() as InboxItem.JobDue).instruction
    return instruction.substringAfter(": ")
}

private fun nowOf(iso: String): Instant = Instant.fromEpochSeconds(java.time.OffsetDateTime.parse(iso).toEpochSecond())

/**
 * Every known swap, rendering with the current production code. [zone] is the person's home zone, for the few results that
 * show a time on their clock.
 */
fun productionSwaps(zone: TimeZone): List<Swap> {
    val prepNow = Playbooks.preparation()
    val helperBase = "Helper R finished its task: T"
    fun helper(
        runId: String = "R", ok: Boolean = true, openTasks: List<String> = emptyList(), check: List<CriterionCheck> = emptyList(),
        checkUnavailable: Boolean = false, fullChars: Int? = null, unreadChanges: Int = 0,
    ) = InboxItem.HelperResult(runId, "T", ok, "", openTasks, check, checkUnavailable, fullChars, 0, false, unreadChanges).summary
        .removePrefix("Helper $runId ${if (ok) "finished" else "failed"} its task: T")
    val date0 = LocalDate(2026, 1, 2)
    fun prep(plan: DayPlan?) = prepInstruction(plan)
    val review by lazy { currentReviewTail() }
    val outing by lazy { outingInstruction(emptyList(), emptyList(), zone) }
    val outingWithContact by lazy { outingInstruction(emptyList(), listOf(RecentContact("C", "N", Instant.fromEpochSeconds(0))), zone) }
    val mcp by lazy {
        val server = HostServer(id = "s", label = "§L§", state = McpServerState.entries.first { it != McpServerState.DISABLED }, tools = listOf(HostTool("t")))
        renderMcpIndex(listOf(McpServer(server, null, listOf(McpToolView(HostTool("t"), McpToolMode.entries.first(), false)))))!!.removeSuffix("\n- §L§")
    }
    fun session(name: String, olds: List<String>, source: String, new: () -> String) =
        Swap(name, source, SwapTarget.SYSTEM, Regex("Session ($UUID): (?:" + alternatives(olds) + ")")) { m ->
            InboxItem.SessionEvent(SessionId(m.groupValues[1]), new()).summary
        }
    fun inbox(text: String, now: Instant, z: TimeZone, clock: Boolean) =
        if (!clock) renderDeliveries(listOf(PendingItem(0, InboxItem.System("§X§"), now)), now, z).last().message.content.filterIsInstance<Block.Text>().single().text
        else renderDeliveries(listOf(PendingItem(0, InboxItem.UserInput(ClientInputId("c"), "§U§"), now)), now, z, statesClock = true)
            .last().message.content.filterIsInstance<Block.Text>().single().text
    val evening = Regex("""Saved for the evening summary at (\S+) \((\w{3})\); it is not shown before then\.""")

    return listOf(
        // -- session playbooks and their wrapper
        session("session CLEAR", FixedTextOld.CLEAR, "sessions/Playbooks.kt:morningCall") { Playbooks.morningCall(CallStage.CLEAR) },
        session("session CHOOSE", FixedTextOld.CHOOSE, "sessions/Playbooks.kt:morningCall") { Playbooks.morningCall(CallStage.CHOOSE) },
        session("session COMMIT", FixedTextOld.COMMIT, "sessions/Playbooks.kt:morningCall") { Playbooks.morningCall(CallStage.COMMIT) },
        session("session HAND_OFF", FixedTextOld.HAND_OFF, "sessions/Playbooks.kt:morningCall") { Playbooks.morningCall(CallStage.HAND_OFF) },
        session("session ended after hand-off", FixedTextOld.ENDED, "sessions/Playbooks.kt:callEnded") { Playbooks.callEnded(true) },
        // -- morning call preparation (SessionJobs.prepInstruction)
        literal("preparation", "sessions/Playbooks.kt:preparation", FixedTextOld.PREP, { prepNow }),
        Swap("preparation: yesterday label", "sessions/SessionJobs.kt:prepInstruction", SwapTarget.SYSTEM, Regex("""\nYesterday \((\d{4}-\d{2}-\d{2})\):""")) { m ->
            prep(DayPlan(LocalDate.parse(m.groupValues[1]), parked = listOf("§K§"))).removePrefix(prepNow).substringBefore("\n- ")
        },
        literal("preparation: priorities label", "sessions/SessionJobs.kt:prepInstruction", listOf("\n- priorities: "), {
            prep(DayPlan(date0, priorities = listOf(Chip("§P§")))).substringAfter("Yesterday ($date0):").substringBefore("§P§")
        }),
        literal("preparation: plan label", "sessions/SessionJobs.kt:prepInstruction", listOf("\n- plan: "), {
            prep(DayPlan(date0, plan = listOf(IfThen("§C§", "§A§")))).substringAfter("Yesterday ($date0):").substringBefore("If §C§")
        }),
        literal("preparation: parked label", "sessions/SessionJobs.kt:prepInstruction", listOf("\n- parked until today: "), {
            prep(DayPlan(date0, parked = listOf("§K§"))).substringAfter("Yesterday ($date0):").substringBefore("§K§")
        }),
        literal("preparation: no plan", "sessions/SessionJobs.kt:prepInstruction", listOf("\nThere is no plan from yesterday."), { prep(null).removePrefix(prepNow) }),
        // -- scheduled jobs
        Swap(
            "periodic review", "scheduler/Jobs.kt:PeriodicReviewJob.fire (run with a capturing inbox)", SwapTarget.SYSTEM,
            Regex("""Scheduled job "periodic-review" is due\. Review what changed since (.+?): (?:${alternatives(FixedTextOld.REVIEW_TAIL)})"""),
        ) { m -> InboxItem.JobDue("periodic-review", "Review what changed since ${m.groupValues[1]}: $review").summary },
        literal("outing scout: head", "outings/OutingPlan.kt:outingInstruction", FixedTextOld.OUTING_HEAD, { outing.substringBefore("\nCompany: only ") }),
        literal(
            "outing scout: company", "outings/OutingPlan.kt:outingInstruction", listOf("these people, all messaged in the last 90 days, nobody else: "),
            { outingWithContact.substringAfter("\nCompany: only ").substringBefore("N (contact C)") },
        ),
        literal("outing scout: brief", "outings/OutingPlan.kt:outingInstruction", FixedTextOld.OUTING_TAIL, { outingWithContact.substringAfter("(contact C).") }),
        // -- nudge notices
        literal(
            "nudge: trailing instruction", "agent/InboxItem.kt:NudgeDue.summary", FixedTextOld.NUDGE_TAIL,
            { InboxItem.NudgeDue("n", "A", "N").summary.removePrefix("Nudge you set at A: N") },
        ),
        // -- helper notices
        Swap("helper: finished or failed", "agent/InboxItem.kt:HelperResult.summary", SwapTarget.SYSTEM, Regex("""Helper ($UUID) (finished|failed) its task(?: \(after revision (\d+)\))?: """)) { m ->
            InboxItem.HelperResult(m.groupValues[1], "", m.groupValues[2] == "finished", "", revision = m.groupValues[3].toIntOrNull() ?: 0).summary
        },
        Swap("helper: open tasks", "agent/InboxItem.kt:HelperResult.summary", SwapTarget.SYSTEM, Regex(""" It worked on task (.+?), still open: update it with update_task, done or failed when it is\.""", RegexOption.DOT_MATCHES_ALL)) { m ->
            helper(openTasks = listOf(m.groupValues[1]))
        },
        Swap("helper: done criteria check", "agent/InboxItem.kt:HelperResult.summary", SwapTarget.SYSTEM, Regex(""" Check of its done criteria: (.+?) \(details below\)\.""")) { m ->
            val probe = listOf(CriterionCheck("c", Verdict.MET, "r"))
            val whole = helper(check = probe)
            val mid = net.robog.clarity.backend.agent.checkSummary(probe)
            whole.substringBefore(mid) + m.groupValues[1] + whole.substringAfter(mid)
        },
        literal(
            "helper: check unavailable", "agent/InboxItem.kt:HelperResult.summary",
            listOf(" The check of its done criteria was unavailable; read the result against them yourself."), { helper(checkUnavailable = true) },
        ),
        Swap("helper: result cut", "agent/InboxItem.kt:HelperResult.summary", SwapTarget.SYSTEM, Regex(""" The result is (\d+) characters and shown cut; read all of it with inspect_helper \(id ($UUID), full true\)\.""")) { m ->
            InboxItem.HelperResult(m.groupValues[2], "T", true, "", fullChars = m.groupValues[1].toInt()).summary.removePrefix("Helper ${m.groupValues[2]} finished its task: T")
        },
        Swap("helper: unread changes", "agent/InboxItem.kt:HelperResult.summary", SwapTarget.SYSTEM, Regex(""" (\d+) of your changes were never read by it; send them with revise_helper if they still matter\.""")) { m ->
            helper(unreadChanges = m.groupValues[1].toInt())
        },
        Swap(
            "helper asks", "agent/InboxItem.kt:HelperAsks.summary", SwapTarget.SYSTEM,
            Regex("""Helper ($UUID) asks you a question \(its task: (.*?)\)\. Answer it with message_helper \(id \1\); it waits for you and goes on by its own judgment if no answer comes\.""", RegexOption.DOT_MATCHES_ALL),
        ) { m -> InboxItem.HelperAsks(m.groupValues[1], 0, m.groupValues[2], "").summary },
        // -- action and held notices
        Swap("action result", "agent/InboxItem.kt:ActionResult.summary", SwapTarget.SYSTEM, Regex("""(?<=\n- )(Done|Could not do): (.+?) \(action ($UUID)\)\.""")) { m ->
            InboxItem.ActionResult(m.groupValues[3], m.groupValues[2], m.groupValues[1] == "Done").summary
        },
        Swap(
            "held decided", "agent/InboxItem.kt:HeldDecided.summary", SwapTarget.SYSTEM,
            Regex(
                """(?<=\n- )The person ruled on the held action "(.+?)" \(($UUID)\): (\w+)\.""" +
                    "( If it was a browser step, delegate a helper to take exactly that step on that page now \\(it snapshots the page again first\\); " +
                    "it goes through without asking\\. A login approval is used by the helper's next sign-in\\.)?",
            ),
        ) { m -> InboxItem.HeldDecided(m.groupValues[2], m.groupValues[3], m.groupValues[1]).summary },
        // -- delivery headers and context notes
        Swap("inbox header", "agent/InboxItem.kt:renderDeliveries", SwapTarget.SYSTEM, Regex("""\[inbox, now (\d{4}-\d{2}-\d{2}T[\d:]+[+-]\d{2}:\d{2}) \((\w{3})\), ([A-Za-z_/]+)]\n- """)) { m ->
            inbox("", nowOf(m.groupValues[1]), TimeZone.of(m.groupValues[3]), clock = false).removeSuffix("§X§")
        },
        Swap("clock line", "agent/InboxItem.kt:renderDeliveries", SwapTarget.SYSTEM, Regex("""\A\[now (\d{4}-\d{2}-\d{2}T[\d:]+[+-]\d{2}:\d{2}) \((\w{3})\), ([A-Za-z_/]+)]\z""")) { m ->
            inbox("", nowOf(m.groupValues[1]), TimeZone.of(m.groupValues[3]), clock = true)
        },
        literal("reply nudge", "agent/LeadAgent.kt:REPLY_NUDGE", listOf(
            "[clarity] The person wrote to you and has had no reply: your plain text is not shown to them. Reply now with send_to_user (a short answer is fine).",
        ), { REPLY_NUDGE }).let { s -> Swap(s.name, s.source, s.target, whole("[clarity] The person wrote to you and has had no reply: your plain text is not shown to them. Reply now with send_to_user (a short answer is fine)."), s.render) },
        Swap("operator update placeholder", "harness/Interrupts.kt:OPERATOR_UPDATE_PLACEHOLDER", SwapTarget.USER, whole("(operator update follows)")) { OPERATOR_UPDATE_PLACEHOLDER },
        literal("memory collections header", "agent/MemoryCollections.kt:COLLECTION_INDEX_HEADER", FixedTextOld.COLLECTIONS_HEAD, { COLLECTION_INDEX_HEADER }),
        literal("mcp servers header", "mcpservers/McpCatalog.kt:renderMcpIndex", FixedTextOld.MCP_HEAD, { mcp }),
        Swap("memory context header", "agent/MemoryStore.kt:renderMemoryContext", SwapTarget.SYSTEM, Regex("""\[memory, (\d{4}-\d{2}-\d{2})]\nJournal, yesterday:""")) { m ->
            val today = LocalDate.parse(m.groupValues[1])
            renderMemoryContext(today, listOf(JournalEntry(1, today.minus(DatePeriod(days = 1)), "§J§")), emptyList(), zone)!!.removeSuffix("\n- §J§")
        },
        literal("memory context: today label", "agent/MemoryStore.kt:renderMemoryContext", listOf("\nJournal, today:"), {
            renderMemoryContext(date0, listOf(JournalEntry(1, date0, "§J§")), emptyList(), zone)!!.removePrefix("[memory, $date0]").removeSuffix("\n- §J§")
        }),
        // -- tool results that tell the model what happened to its message
        Swap("send_to_user: in app", "agent/OwnStateTools.kt:NotifyDecision.describe", SwapTarget.RESULT, whole("Delivered. It shows in the app without a notification.")) { NotifyDecision.ShowInApp.describe(zone) },
        Swap("send_to_user: push now", "agent/OwnStateTools.kt:NotifyDecision.describe", SwapTarget.RESULT, whole("Delivered. The person is being notified now.")) { NotifyDecision.PushNow.describe(zone) },
        Swap(
            "send_to_user: reply", "agent/OwnStateTools.kt:NotifyDecision.describe", SwapTarget.RESULT,
            whole("Delivered as a reply. It shows in the app; the phone notifies only if the app is closed."),
        ) { NotifyDecision.Reply.describe(zone) },
        Swap("send_to_user: evening batch", "agent/OwnStateTools.kt:NotifyDecision.describe", SwapTarget.RESULT, Regex("\\A" + evening.pattern + "\\z")) { m ->
            NotifyDecision.EveningBatch(nowOf(m.groupValues[1])).describe(zone)
        },
    )
}
