package net.robog.clarity.backend.agent

import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.shared.time.ScheduleClock
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.backend.sessions.DayPlanStore
import net.robog.clarity.backend.sessions.Playbooks
import net.robog.clarity.backend.sessions.SessionError
import net.robog.clarity.backend.sessions.SessionRecord
import net.robog.clarity.backend.sessions.SessionService
import net.robog.clarity.backend.sessions.TIERS_QUESTION
import net.robog.clarity.backend.sessions.TierNote
import net.robog.clarity.backend.sessions.amendmentProposed
import net.robog.clarity.backend.sessions.interviewFinished
import net.robog.clarity.backend.sessions.lineCaptured
import net.robog.clarity.backend.sessions.localDate
import net.robog.clarity.backend.sessions.stageDone
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Person
import net.robog.clarity.shared.governance.ProposeAmendment
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionKind

/** The names of the session tools; the lead loads them all the time so its tool list never changes during a session. */
val SESSION_TOOL_NAMES = setOf(
    "capture", "set_priorities", "set_plan", "hand_off", "propose_line", "finalize_constitution", "propose_amendment", "stage_done",
)

private fun SessionKind.label() = when (this) {
    SessionKind.MORNING_CALL -> "morning call"
    SessionKind.VALUES_INTERVIEW -> "values interview"
    SessionKind.AMEND -> "amend session"
}

/**
 * Pure: the live session [record] if it is a [kind] session in one of [stages] (any stage when [stages] is empty);
 * otherwise a message that tells the model what to do instead.
 */
fun requireSession(
    record: SessionRecord?,
    tool: String,
    kind: SessionKind,
    stages: Set<CallStage> = emptySet(),
): Either<String, SessionRecord> = when {
    record == null ->
        "No ${kind.label()} is active, so $tool has nothing to attach to. Use it only while that session is running (the inbox tells you when one starts).".left()
    record.kind != kind ->
        "$tool is for a ${kind.label()}, but the active session is a ${record.kind.label()}.".left()
    record.state == Session.State.SNOOZED ->
        "The ${kind.label()} is snoozed (the person declined the call); wait until it rings again.".left()
    stages.isNotEmpty() && record.session.stage !in stages ->
        "$tool works in the ${stages.joinToString(" or ") { it.name }} stage, but the call is at ${record.session.stage?.name}.".left()
    else -> record.right()
}

/** Pure: [existing] plus the [added] items that are not in it yet (compared ignoring case and spaces), in order. */
fun mergeCaptured(existing: List<Chip>, added: List<Chip>): List<Chip> {
    val seen = existing.map { it.text.trim().lowercase() }.toMutableSet()
    return existing + added.filter { seen.add(it.text.trim().lowercase()) }
}

/** Pure: the constitution a finished values interview writes. */
fun buildConstitution(
    interviewLines: List<Line>,
    fixedLines: List<Line>,
    autonomy: Map<ActionKind, AutonomyTier>,
    getsThrough: List<Person>,
    protectedTimes: List<TimeBlock>,
    neverStore: List<String>,
    profile: List<String>,
    now: kotlin.time.Instant,
): Constitution = Constitution(
    version = 1,
    lines = interviewLines + fixedLines,
    autonomy = autonomy,
    getsThrough = getsThrough,
    protectedTimes = protectedTimes,
    neverStore = neverStore.map(String::trim).filter(String::isNotEmpty).distinct(),
    profile = profile.map(String::trim).filter(String::isNotEmpty).distinct(),
    updatedAt = now,
)

/** Pure: a clock time from "HH:MM" (or "H:MM"). */
fun parseClock(raw: String): LocalTime? = runCatching {
    val (h, m) = raw.trim().split(':')
    LocalTime(h.toInt(), m.toInt())
}.getOrNull()

private val TIER_SUB = mapOf(
    AutonomyTier.DO_THEN_TELL to "do it, then tell me",
    AutonomyTier.ASK_FIRST to "ask me first",
    AutonomyTier.NEVER_ALONE to "never alone",
    AutonomyTier.NEVER to "never",
)

/**
 * The lead's tools for sessions: the morning call (`capture`, `set_priorities`, `set_plan`,
 * `hand_off`), the values interview (`propose_line`, `finalize_constitution`), amending
 * (`propose_amendment`) and `stage_done`. They are always loaded; each fails with a clear message
 * when no matching session is active, so the lead's tool list (and its cache) never changes.
 */
@Inject
@SingleIn(AppScope::class)
class SessionTools(
    private val sessions: SessionService,
    private val feed: FeedService,
    private val plans: DayPlanStore,
    private val tasks: TaskStore,
    private val constitutions: ConstitutionStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    fun all(): List<Tool> = listOf(
        capture(), setPriorities(), setPlan(), handOff(), proposeLine(), finalizeConstitution(), proposeAmendment(), stageDone(),
    )

    private suspend fun live() = sessions.live()

    private suspend fun today(): LocalDate = localDate(clock.now(), settings.current().homeZone)

    /** Keyed by the tool call, so a call re-run after a crash does not post its card twice. */
    private suspend fun callKey(): String? =
        coroutineContext[ToolCallContext]?.let { "tool:${it.runId}:${it.toolUseId}" }

    private suspend fun post(session: SessionRecord, cards: List<Card>) {
        feed.append(
            FeedDraft(
                author = FeedItem.Author.AGENT,
                cards = cards,
                sessionId = session.id,
                clientInputId = callKey()?.let(::ClientInputId),
                notify = "in_app",
            ),
        )
    }

    private fun SessionError.text() = "$title: $detail"

    // ---- morning call -------------------------------------------------------------------------

    @Serializable
    class ItemInput(
        @Description("What it is, a few words (\"call the dentist\").") val text: String,
        @Description("Optional small detail, usually empty.") val sub: String = "",
    )

    @Serializable
    class CaptureInput(@Description("What the person just mentioned, one short item each. Only the new things; earlier ones are kept.") val items: List<ItemInput>)

    private fun capture(): Tool = tool<CaptureInput>(
        name = "capture",
        description = "Morning call, CLEAR stage: record what is on the person's mind. Shows (and keeps updating) one \"captured · N\" card. Call it again as more comes up.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val added = input.items.map { Chip(it.text.trim(), it.sub.trim()) }.filter { it.text.isNotEmpty() }
        if (added.isEmpty()) return@tool ToolResult.Error("`items` needs at least one non-empty item.")
        val session = requireSession(live(), "capture", SessionKind.MORNING_CALL, setOf(CallStage.CLEAR)).getOrElse { return@tool ToolResult.Error(it) }
        val key = callKey()
        val updated = sessions.updateData(session.id) { it.copy(captured = mergeCaptured(it.captured, added)) }
            .getOrElse { return@tool ToolResult.Error(it.text()) }
        val chips = updated.data.captured
        val cards = listOf<Card>(Card.Chips("captured · ${chips.size}", chips))
        val itemId = updated.data.capturedItemId
        if (itemId == null) {
            val appended = feed.append(
                FeedDraft(FeedItem.Author.AGENT, cards, session.id, clientInputId = key?.let(::ClientInputId), notify = "in_app"),
            )
            sessions.updateData(session.id) { it.copy(capturedItemId = appended.item.id.value) }
        } else {
            feed.finish(FeedItemId(itemId), cards)
        }
        ToolResult.Ok("Captured ${chips.size} so far: ${chips.joinToString("; ") { it.text }}.")
    }

    @Serializable
    class PriorityInput(
        @Description("The priority in the person's words.") val text: String,
        @Description("A few words on why, e.g. \"important\" or \"due today\".") val note: String = "",
    )

    @Serializable
    class SetPrioritiesInput(
        @Description("The one they would regret not moving forward, ignoring deadlines.") val important: PriorityInput,
        @Description("At most one more that has to happen today.") val also: PriorityInput? = null,
    )

    private fun setPriorities(): Tool = tool<SetPrioritiesInput>(
        name = "set_priorities",
        description = "Morning call, CHOOSE stage: record today's two (the important one, and at most one more). Shows the \"today's two\" card and saves it for Today.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "set_priorities", SessionKind.MORNING_CALL, setOf(CallStage.CHOOSE)).getOrElse { return@tool ToolResult.Error(it) }
        val chips = listOfNotNull(
            Chip(input.important.text.trim(), input.important.note.trim().ifEmpty { "important" }),
            input.also?.let { Chip(it.text.trim(), it.note.trim().ifEmpty { "also" }) },
        )
        if (chips.any { it.text.isEmpty() }) return@tool ToolResult.Error("A priority needs text.")
        plans.update(today()) { it.copy(priorities = chips, sessionId = session.id.value) }
        post(session, listOf(Card.Chips("today’s two", chips)))
        ToolResult.Ok("Priorities saved: ${chips.joinToString("; ") { it.text }}.")
    }

    @Serializable
    class IfThenInput(
        @Description("The cue, written to follow \"If\": \"it's 9:30\", \"Dana emails\".") val cue: String,
        @Description("What happens then, written to follow \"then\": \"spec at my desk until 12\".") val action: String,
    )

    @Serializable
    class SetPlanInput(@Description("One to four if-then plans: when and where, and what to do when the likeliest obstacle shows up.") val ifThens: List<IfThenInput>)

    private fun setPlan(): Tool = tool<SetPlanInput>(
        name = "set_plan",
        description = "Morning call, COMMIT stage: record the if-then plans. Shows the \"your plan\" card and saves it for Today.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "set_plan", SessionKind.MORNING_CALL, setOf(CallStage.COMMIT)).getOrElse { return@tool ToolResult.Error(it) }
        val items = input.ifThens.map { IfThen(it.cue.trim().removePrefix("If ").removePrefix("if "), it.action.trim().removePrefix("then ")) }
        if (items.isEmpty() || items.size > MAX_PLANS) return@tool ToolResult.Error("Give 1 to $MAX_PLANS if-then plans.")
        if (items.any { it.cue.isEmpty() || it.action.isEmpty() }) return@tool ToolResult.Error("Each plan needs a cue and an action.")
        plans.update(today()) { it.copy(plan = items, sessionId = session.id.value) }
        post(session, listOf(Card.Plan(items)))
        ToolResult.Ok("Plan saved (${items.size}).")
    }

    @Serializable
    class TaskInput(
        @Description("What you will do, a few words (\"hold 9:30–12 for the spec\").") val title: String,
        @Description("Optional detail for the task list.") val detail: String = "",
    )

    @Serializable
    class HandOffInput(
        @Description("Things you will do on your own (they stay with the person: holds, reminders, lookups, drafts they will not need to review).")
        val alone: List<TaskInput> = emptyList(),
        @Description("Things you prepare but the person reviews first (anything that goes out as them).")
        val review: List<TaskInput> = emptyList(),
        @Description("Things to leave until tomorrow, a few words each.") val parked: List<String> = emptyList(),
    )

    private fun handOff(): Tool = tool<HandOffInput>(
        name = "hand_off",
        description = "Morning call, HAND_OFF stage: create the tasks for what you will do (alone / for review) and park the rest. Shows \"I'll do\" and \"parked until tomorrow\". " +
            "Call it once. The actions themselves are carried out after the call with the usual tools.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "hand_off", SessionKind.MORNING_CALL, setOf(CallStage.HAND_OFF)).getOrElse { return@tool ToolResult.Error(it) }
        val alone = input.alone.filter { it.title.isNotBlank() }
        val review = input.review.filter { it.title.isNotBlank() }
        val parked = input.parked.map(String::trim).filter(String::isNotEmpty)
        if (alone.isEmpty() && review.isEmpty() && parked.isEmpty()) return@tool ToolResult.Error("Nothing to hand off: give at least one of alone, review or parked.")
        val key = callKey() ?: "manual"
        var duplicate = false
        sessions.updateData(session.id) { data ->
            if (data.handOffKey != null) { duplicate = true; data } else data.copy(handOffKey = key)
        }.getOrElse { return@tool ToolResult.Error(it.text()) }
        if (duplicate) {
            val earlier = sessions.live()?.data?.handOffKey
            return@tool if (earlier == key) ToolResult.Ok("Already handed off.")
            else ToolResult.Error("The hand-off was already made in this call. Adjust single tasks with update_task.")
        }
        val created = (alone.map { it to "alone" } + review.map { it to "you review" }).map { (item, sub) ->
            tasks.upsert(null, item.title.trim(), item.detail.trim().ifEmpty { sub }, AgentTask.Status.WORKING)!! to sub
        }
        plans.update(today()) { it.copy(parked = parked, called = true, sessionId = session.id.value) }
        val cards = buildList<Card> {
            if (created.isNotEmpty()) add(Card.Chips("I’ll do", created.map { (task, sub) -> Chip(task.title, sub) }))
            if (parked.isNotEmpty()) add(Card.Chips("parked until tomorrow", parked.map { Chip(it) }))
            if (created.isNotEmpty()) add(Card.Tasks("setting up", created.map { (task, _) -> task.id }))
        }
        post(session, cards)
        ToolResult.Ok(
            "Handed off: ${created.size} task(s) created" + created.joinToString(prefix = " (", postfix = ")") { (t, _) -> "${t.id.value}: ${t.title}" } +
                ", ${parked.size} parked. Now call stage_done; carry the tasks out after the call ends.",
        )
    }

    // ---- values interview ---------------------------------------------------------------------

    @Serializable
    class TierInput(
        val level: AutonomyTier,
        @Description("The examples the person gave for this tier, in their words.") val examples: String,
    )

    @Serializable
    class ProposeLineInput(
        val section: Section,
        @Description("The line, first person, in the person's own words (\"Never speak for me to a group without my review.\").") val text: String,
        @Description("Their reason, in their words, starting lower-case (\"being misrepresented costs more than any time saved.\").") val because: String,
        @Description("Question 3 only: the autonomy tiers (do it then tell me, ask me first, never alone, never) with the person's examples.") val tiers: List<TierInput>? = null,
        @Description("True for a hard line that is never crossed.") val hard: Boolean = false,
    )

    private fun proposeLine(): Tool = tool<ProposeLineInput>(
        name = "propose_line",
        description = "Values interview: write the line for the current question (or a different phrasing after \"reword\"). Shows the \"captured as\" card and lets the person keep it.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "propose_line", SessionKind.VALUES_INTERVIEW).getOrElse { return@tool ToolResult.Error(it) }
        val index = session.session.question ?: 0
        if (input.text.isBlank() || input.because.isBlank()) return@tool ToolResult.Error("A line needs `text` and the person's reason in `because`.")
        val question = Playbooks.QUESTIONS.getOrNull(index)
        val line = Line(
            id = LineId("q${index + 1}-${input.section.wire()}"),
            section = input.section,
            text = input.text.trim(),
            because = input.because.trim(),
            hard = input.hard || input.section == Section.HARD_LINES,
        )
        val tiers = if (index == TIERS_QUESTION) input.tiers.orEmpty().map { TierNote(it.level, it.examples.trim()) } else emptyList()
        if (index != TIERS_QUESTION && input.tiers != null) return@tool ToolResult.Error("`tiers` belongs to question ${TIERS_QUESTION + 1} only.")
        sessions.mutate(session.id, quiet = true) { it.lineCaptured(line, tiers) }.getOrElse { return@tool ToolResult.Error(it.text()) }
        val cards = buildList<Card> {
            add(Card.Chips("captured as · ${question?.label ?: "line"}", listOf(Chip(line.text, line.because?.let { "because $it" }.orEmpty()))))
            if (tiers.isNotEmpty()) add(Card.Chips("how much I do alone", tiers.map { Chip(it.examples, TIER_SUB.getValue(it.level)) }))
        }
        post(session, cards)
        ToolResult.Ok("Captured line for question ${index + 1}. The person can keep it or ask to reword. Do not ask the next question; the app moves on when they keep it.")
    }

    @Serializable
    class AutonomyInput(val kind: ActionKind, val tier: AutonomyTier)

    @Serializable
    class PersonInput(
        val name: String,
        @Description("Emails or phone numbers, if the person gave or you know them.") val handles: List<String> = emptyList(),
    )

    @Serializable
    class TimeBlockInput(
        @Description("e.g. \"deep work\", \"family\".") val label: String,
        @Description("ISO weekdays, 1 = Monday, as the person said them.") val days: List<Int>,
        @Description("Start on the person's clock, HH:MM, as they said it.") val start: String,
        @Description("End on the person's clock, HH:MM, as they said it.") val end: String,
    )

    @Serializable
    class FixedLineInput(
        val section: Section,
        val text: String,
        val hard: Boolean = false,
    )

    @Serializable
    class FinalizeInput(
        @Description("Extra fixed lines the person stated that are not one of the seven answers (\"Never move money or buy anything on my own.\"). Usually 0-3.")
        val lines: List<FixedLineInput> = emptyList(),
        @Description("Autonomy per action kind from question 3. Kinds not listed mean \"ask first\". Payment is never alone.")
        val autonomy: List<AutonomyInput> = emptyList(),
        @Description("The people who get through protected time.") val getsThrough: List<PersonInput> = emptyList(),
        val protectedTimes: List<TimeBlockInput> = emptyList(),
        @Description("Topics never to store (\"health\").") val neverStore: List<String> = emptyList(),
        @Description("Short durable facts about the person learned in the interview.") val profile: List<String> = emptyList(),
    )

    private fun finalizeConstitution(): Tool = tool<FinalizeInput>(
        name = "finalize_constitution",
        description = "Values interview, after the last question is kept: write constitution version 1 from the kept lines plus these structured parts, and end the interview.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "finalize_constitution", SessionKind.VALUES_INTERVIEW).getOrElse { return@tool ToolResult.Error(it) }
        if (!session.finalizing) {
            return@tool ToolResult.Error("The interview is not finished: the person is on question ${(session.session.question ?: 0) + 1} of ${session.session.questionCount}. Wait until they keep the last line.")
        }
        // Said on the person's home clock; stored in UTC.
        val home = zoneOrUtc(settings.current().homeZone)
        val now = clock.now()
        val blocks = input.protectedTimes.map { b ->
            val start = parseClock(b.start) ?: return@tool ToolResult.Error("`start` of \"${b.label}\" must be HH:MM.")
            val end = parseClock(b.end) ?: return@tool ToolResult.Error("`end` of \"${b.label}\" must be HH:MM.")
            if (b.days.isEmpty() || b.days.any { it !in 1..7 }) return@tool ToolResult.Error("`days` of \"${b.label}\" are ISO weekdays 1 (Monday) to 7.")
            ScheduleClock.blockToStored(TimeBlock(b.label, b.days.toSet(), start, end), home, home, now)
        }
        val fixed = input.lines.filter { it.text.isNotBlank() }.mapIndexed { i, l ->
            Line(LineId("fixed-${i + 1}"), l.section, l.text.trim(), because = null, hard = l.hard || l.section == Section.HARD_LINES)
        }
        val constitution = buildConstitution(
            interviewLines = session.data.lines,
            fixedLines = fixed,
            autonomy = input.autonomy.associate { it.kind to it.tier },
            getsThrough = input.getsThrough.map { p -> Person(p.name.trim(), p.handles.map(::parseHandle)) },
            protectedTimes = blocks,
            neverStore = input.neverStore,
            profile = input.profile,
            now = clock.now(),
        )
        constitutions.create(constitution).fold(
            { ToolResult.Error("The constitution was not written: ${it.title}. ${it.detail.orEmpty()}") },
            { written ->
                sessions.mutate(session.id, quiet = true) { it.interviewFinished(clock.now()) }
                ToolResult.Ok("Constitution version ${written.version} written with ${written.lines.size} lines. Tell the person it is ready.")
            },
        )
    }

    // ---- amend --------------------------------------------------------------------------------

    @Serializable
    class ProposeAmendmentInput(
        @Description("The id of the constitution line to replace; omit to add a new line.") val lineId: String? = null,
        @Description("The new text of the line, first person, in the person's words.") val text: String,
        @Description("Their reason, in their words.") val because: String,
        @Description("One sentence for the person: why this change.") val reason: String,
        @Description("The section for a NEW line (default values); ignored when replacing a line.") val section: Section? = null,
    )

    private fun proposeAmendment(): Tool = tool<ProposeAmendmentInput>(
        name = "propose_amendment",
        description = "Amend session: propose changing one rule (or adding one). It does nothing until the person saves it; the session ends when they save or discard it. Hard lines cannot be changed this way.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val session = requireSession(live(), "propose_amendment", SessionKind.AMEND).getOrElse { return@tool ToolResult.Error(it) }
        if (input.text.isBlank() || input.because.isBlank() || input.reason.isBlank()) {
            return@tool ToolResult.Error("`text`, `because` and `reason` are all needed.")
        }
        val current = constitutions.current() ?: return@tool ToolResult.Error("There is no constitution to amend yet; the values interview comes first.")
        val target = input.lineId?.let { id ->
            current.lines.firstOrNull { it.id.value == id } ?: return@tool ToolResult.Error(
                "No line '$id'. Lines: " + current.lines.joinToString("; ") { "${it.id.value} = ${it.text.take(60)}" },
            )
        }
        if (target?.hard == true) return@tool ToolResult.Error("\"${target.text.take(60)}\" is a hard line. Hard lines are never changed by talking; the person can edit it in the constitution screen.")
        val proposed = Line(
            id = target?.id ?: LineId("amended-${Uuid.random().toString().take(8)}"),
            section = target?.section ?: input.section ?: Section.VALUES,
            text = input.text.trim(),
            because = input.because.trim(),
            hard = false,
        )
        // A newer proposal replaces an older one that was never decided.
        session.session.amendmentId?.let { old -> constitutions.discardAmendment(old) }
        constitutions.propose(ProposeAmendment(target?.id, proposed, input.reason.trim(), session.session.heldActionId)).fold(
            { ToolResult.Error("The amendment was not proposed: ${it.title}. ${it.detail.orEmpty()}") },
            { amendment ->
                sessions.mutate(session.id, quiet = true) { it.amendmentProposed(amendment.id) }
                ToolResult.Ok("Proposed amendment ${amendment.id.value}; it waits for the person to save or discard it. Say in one sentence what it changes.")
            },
        )
    }

    // ---- all sessions -------------------------------------------------------------------------

    @Serializable
    class NoInput

    private fun stageDone(): Tool = tool<NoInput>(
        name = "stage_done",
        description = "Say that you have finished producing this step's output (the question asked, the stage opened, the line captured): the person's \"next\" button enables. Call it last in your turn.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) {
        val record = live() ?: return@tool ToolResult.Error("No session is active, so there is nothing to mark done.")
        sessions.mutate(record.id, quiet = true) { it.stageDone() }.fold(
            { ToolResult.Error(it.text()) },
            { ToolResult.Ok("Marked done. The person can move on when they are ready.") },
        )
    }

    private companion object { const val MAX_PLANS = 4 }
}

/** Pure: whether a registration list contains every session tool (used to keep the lead's loaded set honest). */
fun containsAllSessionTools(names: Collection<String>): Boolean = names.containsAll(SESSION_TOOL_NAMES)
