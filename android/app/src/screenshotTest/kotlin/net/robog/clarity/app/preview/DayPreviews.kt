package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.app.ui.amend.AmendContent
import net.robog.clarity.app.ui.amend.AmendUiState
import net.robog.clarity.app.ui.call.CallContent
import net.robog.clarity.app.ui.call.SlideToAnswer
import net.robog.clarity.app.ui.call.CallScreen
import net.robog.clarity.app.ui.call.CallUiState
import net.robog.clarity.app.ui.components.SlideToConfirm
import net.robog.clarity.app.ui.constitution.ConstitutionContent
import net.robog.clarity.app.ui.constitution.ConstitutionUiState
import net.robog.clarity.app.ui.held.DraftReviewContent
import net.robog.clarity.app.ui.held.DraftReviewState
import net.robog.clarity.app.ui.held.LocalHeldExtras
import net.robog.clarity.app.ui.held.NotNowOptions
import net.robog.clarity.app.ui.held.RuleDialog
import net.robog.clarity.app.ui.main.TabBar
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.today.Doing
import net.robog.clarity.app.ui.today.DoingRow
import net.robog.clarity.app.ui.today.doingRows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.app.ui.today.TodayContent
import net.robog.clarity.app.ui.today.TodayUiState
import net.robog.clarity.app.vault.vaultExtras
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.RuleDraft
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Today
import net.robog.clarity.shared.vault.CredentialId

private val t0 = Instant.parse("2026-10-26T14:04:00Z")
private val mic = MicAccess(granted = true, refused = false, request = {}, openSettings = {})
private val noMic = MicAccess(granted = false, refused = false, request = {}, openSettings = {})

@Composable
private fun Screen(dark: Boolean, content: @Composable () -> Unit) {
    ClarityTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalHeldExtras provides vaultExtras) { content() }
        }
    }
}

// ---------- Today ----------

private fun task(id: String, title: String, detail: String, status: AgentTask.Status, held: String? = null, ago: Duration = Duration.ZERO) =
    AgentTask(TaskId(id), title, detail, status, held?.let { HeldActionId(it) }, t0 - ago)

private val todayFull = TodayUiState(
    loading = false,
    kicker = "monday, October 26 · 2:04 pm",
    today = Today(
        date = LocalDate(2026, 10, 26),
        plan = listOf(
            IfThen("it’s 9:30", "spec at my desk until 12, phone face down"),
            IfThen("Dana emails about the contract", "it waits until 12 unless she says it’s urgent"),
            IfThen("it’s 4:00", "walk before the school pickup"),
        ),
        parked = listOf("feedback for Alex", "expense report", "renewing the car registration"),
        called = true,
    ),
    // Grouped by the app's own rule at t0: needs you, running, scheduled; stale ones folded away.
    doing = doingRows(
        listOf(
            task("q1", "Follow up with Nick about The Devils", "mail · waiting for the sent-mail sync fix", AgentTask.Status.WAITING_FOR_YOU, ago = 20.hours),
            task("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED, ago = 2.hours),
            task("q2", "Find Ulta haircut openings", "web · no openings this week", AgentTask.Status.WAITING_FOR_YOU, ago = 14.hours),
            task("t2", "Find a time with Nick for the Devils game", "calendar · checking both calendars", AgentTask.Status.WORKING, ago = 5.minutes),
            task("q3", "Sort the receipts folder", "files · started on Friday", AgentTask.Status.WORKING, ago = 3.days),
            task("t1", "Reply to Dana about the revised contract terms", "mail · waits for your review", AgentTask.Status.DRAFTED, "h1", ago = 20.minutes),
        ),
        emptyMap(),
        t0,
    ),
    heldWaiting = 2,
)

@Composable private fun TodayFull(dark: Boolean, quietOpen: Boolean = false) = Screen(dark) { TodayContent(todayFull, {}, {}, {}, quietExpanded = quietOpen) }

/** The full day with helpers that finished today: one done in 80 seconds, one failed, one cancelled. */
private val todayFinished = todayFull.copy(
    doing = doingRows(
        listOf(
            task("q1", "Follow up with Nick about The Devils", "mail · waiting for the sent-mail sync fix", AgentTask.Status.WAITING_FOR_YOU, ago = 20.hours),
            task("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED, ago = 2.hours),
            task("t2", "Find a time with Nick for the Devils game", "calendar · checking both calendars", AgentTask.Status.WORKING, ago = 5.minutes),
            task("t1", "Reply to Dana about the revised contract terms", "mail · waits for your review", AgentTask.Status.DRAFTED, "h1", ago = 20.minutes),
            task("f1", "Check the dentist’s opening hours", "web · open until 6 today", AgentTask.Status.DONE, ago = 12.minutes),
            task("f2", "Book the car service", "web · the booking page timed out", AgentTask.Status.FAILED, ago = 1.hours),
            task("f3", "Remind Sam about the permission slip", "no longer needed", AgentTask.Status.CANCELLED, ago = 3.hours),
        ),
        emptyMap(),
        t0,
    ),
)

@Composable private fun TodayFinished(dark: Boolean, open: Boolean) = Screen(dark) {
    TodayContent(todayFinished, {}, {}, {}, quietExpanded = open, finishedExpanded = open)
}
@PreviewTest @Preview(widthDp = 412, heightDp = 1000) @Composable fun TodayFinishedFoldedLight() = TodayFinished(false, open = false)
@PreviewTest @Preview(widthDp = 412, heightDp = 1000) @Composable fun TodayFinishedFoldedDark() = TodayFinished(true, open = false)
@PreviewTest @Preview(widthDp = 412, heightDp = 1250) @Composable fun TodayFinishedOpenLight() = TodayFinished(false, open = true)
@PreviewTest @Preview(widthDp = 412, heightDp = 1250) @Composable fun TodayFinishedOpenDark() = TodayFinished(true, open = true)

@Composable private fun TodayEmpty(dark: Boolean) = Screen(dark) {
    TodayContent(TodayUiState(loading = false, kicker = "monday, October 26 · 2:04 pm", today = Today(date = LocalDate(2026, 10, 26))), {}, {}, {})
}

@Composable private fun TodayError(dark: Boolean) = Screen(dark) {
    TodayContent(TodayUiState(loading = false, unavailable = true, error = "Can’t reach your server. Check your connection."), {}, {}, {})
}

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TodayFullLight() = TodayFull(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TodayFullDark() = TodayFull(true)
// The quieter tasks unfolded: compact, still rows with nothing to press.
@PreviewTest @Preview(widthDp = 412, heightDp = 1100) @Composable fun TodayQuietOpenLight() = TodayFull(false, quietOpen = true)
@PreviewTest @Preview(widthDp = 412, heightDp = 1100) @Composable fun TodayQuietOpenDark() = TodayFull(true, quietOpen = true)
// This morning's call left unfinished: "call me again" above the day.
@Composable private fun TodayCallAgain(dark: Boolean) = Screen(dark) { TodayContent(todayFull.copy(callAgain = true), {}, {}, {}) }
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TodayCallAgainLight() = TodayCallAgain(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TodayCallAgainDark() = TodayCallAgain(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun TodayEmptyLight() = TodayEmpty(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun TodayEmptyDark() = TodayEmpty(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun TodayErrorLight() = TodayError(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun TodayErrorDark() = TodayError(true)

// One "I'm doing" row per task status: running work gets a drawn solid, the rest a still shape.
private val everyStatus = todayFull.copy(
    doing = Doing(
        main = AgentTask.Status.entries.mapIndexed { i, status ->
            val waiting = status == AgentTask.Status.DRAFTED || status == AgentTask.Status.WAITING_FOR_YOU
            val held = if (waiting) "s$i" else null
            DoingRow(task("s$i", "A task that is ${status.name.lowercase().replace('_', ' ')}", "mail · detail line", status, held), held?.let { HeldActionId(it) })
        } + DoingRow(task("sw", "A task that waits with nothing to review", "chat · asked a question", AgentTask.Status.WAITING_FOR_YOU), null),
    ),
)
@PreviewTest @Preview(widthDp = 412, heightDp = 1300) @Composable fun TodayEveryStatusLight() = Screen(false) { TodayContent(everyStatus, {}, {}, {}) }
@PreviewTest @Preview(widthDp = 412, heightDp = 1300) @Composable fun TodayEveryStatusDark() = Screen(true) { TodayContent(everyStatus, {}, {}, {}) }

// ---------- Draft review ----------

private val danaBody = """
    Hi Dana,

    Thanks for sending over the revised terms. I've gone through them and I'm happy with sections 2 through 5. On section 6, the payment schedule, could we move the second installment to the end of November instead of the 15th? That lines up better with our invoicing cycle.

    If that works for you, I can sign by Friday as planned. Would Thursday at 2:00 work for a quick call to finalize?

    Best,
    Sam
""".trimIndent()

private val emailHeld = HeldAction(
    id = HeldActionId("h1"),
    createdAt = t0,
    action = ProposedAction.SendEmail(
        idempotencyKey = "k1",
        summary = "Reply to Dana about the revised contract terms",
        email = EmailDraft(to = listOf(Handle.email("dana.whitfield@hartleyandco.com", "Dana Whitfield")), subject = "Re: Revised Terms — Q4 services agreement", body = danaBody),
    ),
    verdict = GuardVerdict(Decision.ASK, lines = listOf(LineId("l2"), LineId("l5")), rationale = "I’m asking because it goes out as you and touches money (line 2)."),
    agentView = "She asked for a reply before Friday. I agreed to everything except the payment date, which clashes with your invoicing. I kept it short and offered Thursday 2:00, which is clear for both of you.",
    context = listOf(
        ContextItem("mail · yesterday", "Re: Revised Terms — “Can you confirm by Friday? I’ve attached v3 with the changes we discussed.”"),
        ContextItem("files · tue", "Contract_v3.pdf"),
    ),
    urgency = Urgency.NORMAL,
    status = HeldAction.Status.HELD,
    ruleDraft = RuleDraft("Replies to Dana about the contract can go out without asking: send them, then tell me.", "she’s a long-time client and these are routine"),
)

private val codeHeld = HeldAction(
    id = HeldActionId("h2"),
    createdAt = t0,
    action = ProposedAction.AskCode("k2", "Sign in to your Chase account to download October’s statement", site = "chase.com", purpose = "download the October statement"),
    verdict = GuardVerdict(Decision.ASK, rationale = "Codes always come from you."),
    agentView = "Chase sent a six-digit code to your phone ending in 42. I need it to finish signing in.",
    urgency = Urgency.URGENT,
    status = HeldAction.Status.HELD,
)

private val credentialHeld = HeldAction(
    id = HeldActionId("h3"),
    createdAt = t0,
    action = ProposedAction.UseCredential("k3", "Use your saved login for the school portal", CredentialId("c1"), site = "parentportal.lincolnelementary.org", fields = listOf("username", "password")),
    verdict = GuardVerdict(Decision.ASK, rationale = "Saved logins are only opened on your phone.", phoneApproval = true),
    agentView = "I’m on the portal to sign Maya up for the field trip. It needs your login to get to the permission form.",
    urgency = Urgency.NORMAL,
    status = HeldAction.Status.HELD,
)

private val moveHeld = HeldAction(
    id = HeldActionId("h4"),
    createdAt = t0,
    action = ProposedAction.MoveEvent("k4", "Move call with Dana to Thursday 2:00", EntityId("e"), t0, t0, notifyAttendees = true),
    verdict = GuardVerdict(Decision.ASK, rationale = "It’s her invite, so the update goes out to her."),
    agentView = "Thursday 2:00 is clear for both of you.",
    urgency = Urgency.NORMAL,
    status = HeldAction.Status.EXECUTED,
)

@Composable private fun ReviewEmail(dark: Boolean) = Screen(dark) {
    DraftReviewContent(DraftReviewState(loading = false, held = emailHeld, body = danaBody), {}, {}, {}, {})
}
@Composable private fun ReviewCode(dark: Boolean) = Screen(dark) {
    DraftReviewContent(DraftReviewState(loading = false, held = codeHeld), {}, {}, {}, {})
}
@Composable private fun ReviewCredential(dark: Boolean) = Screen(dark) {
    DraftReviewContent(DraftReviewState(loading = false, held = credentialHeld), {}, {}, {}, {})
}
@Composable private fun ReviewSettledError(dark: Boolean) = Screen(dark) {
    DraftReviewContent(DraftReviewState(loading = false, held = moveHeld, message = "Couldn’t reach your server. Try again."), {}, {}, {}, {})
}
@Composable private fun ReviewMissing(dark: Boolean) = Screen(dark) {
    DraftReviewContent(DraftReviewState(loading = false, held = null, message = "It may have been decided on another device."), {}, {}, {}, {})
}

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewEmailLight() = ReviewEmail(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewEmailDark() = ReviewEmail(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewCodeLight() = ReviewCode(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewCodeDark() = ReviewCode(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewCredentialLight() = ReviewCredential(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun ReviewCredentialDark() = ReviewCredential(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 700) @Composable fun ReviewSettledLight() = ReviewSettledError(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 700) @Composable fun ReviewSettledDark() = ReviewSettledError(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 400) @Composable fun ReviewMissingLight() = ReviewMissing(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 400) @Composable fun ReviewMissingDark() = ReviewMissing(true)

@Composable private fun NotNowFrame(dark: Boolean) = Screen(dark) {
    NotNowOptions(emailHeld, {}, {})
}
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun NotNowLight() = NotNowFrame(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun NotNowDark() = NotNowFrame(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun RuleDialogLight() = Screen(false) { Box(Modifier.fillMaxSize()) { RuleDialog(emailHeld, { _, _ -> }, {}) } }

@Composable private fun Sliders(dark: Boolean) = Screen(dark) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SlideToConfirm("slide to send", {})
        SlideToConfirm("slide to send code", {}, enabled = false)
        SlideToConfirm("slide to add to calendar", {})
    }
}
@PreviewTest @Preview(widthDp = 412) @Composable fun SliderLight() = Sliders(false)
@PreviewTest @Preview(widthDp = 412) @Composable fun SliderDark() = Sliders(true)

// ---------- Constitution ----------

private val constitution = Constitution(
    version = 3,
    lines = listOf(
        Line(LineId("l1"), Section.VALUES, "Mornings are for deep work. Nothing interrupts 9 to 12 unless it’s my family or my doctor."),
        Line(LineId("l2"), Section.HARD_LINES, "Never send money or agree to a payment without asking me first.", hard = true),
        Line(LineId("l3"), Section.PEOPLE, "Dana and Nick can always get through."),
        Line(LineId("l4"), Section.AUTONOMY, "Calendar holds for myself are fine: do it, then tell me."),
        Line(LineId("l5"), Section.COLLISIONS, "When work and the kids collide, the kids win, and tell the other person kindly."),
        Line(LineId("l6"), Section.REPAIR, "If you get something wrong, tell me plainly and fix it before anything else."),
    ),
    autonomy = mapOf(ActionKind.CREATE_EVENT to AutonomyTier.DO_THEN_TELL, ActionKind.CREATE_REMINDER to AutonomyTier.DO_THEN_TELL, ActionKind.SAVE_DRAFT to AutonomyTier.DO_THEN_TELL),
    updatedAt = t0,
)

@Composable private fun ConstitutionFull(dark: Boolean) = Screen(dark) {
    ConstitutionContent(ConstitutionUiState(loading = false, constitution = constitution), {}, {})
}
@PreviewTest @Preview(widthDp = 412, heightDp = 1100) @Composable fun ConstitutionLight() = ConstitutionFull(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 1100) @Composable fun ConstitutionDark() = ConstitutionFull(true)

// ---------- Amend ----------

private val amendHeld = emailHeld.copy(status = HeldAction.Status.HELD)
private fun fi(seq: Long, author: FeedItem.Author, vararg cards: Card) = FeedItem(FeedItemId("a$seq"), seq, t0, author, cards = cards.toList())
private val amendState = AmendUiState(
    loading = false,
    held = amendHeld,
    session = Session(SessionId("s1"), SessionKind.AMEND, Session.State.ACTIVE, t0),
    talk = listOf(
        fi(1, FeedItem.Author.AGENT, Card.Text("This hit line 2 because it mentions a payment date. Should it have?")),
        fi(2, FeedItem.Author.USER, Card.Said("No, moving a date isn’t agreeing to pay. Replies to Dana are fine.", InputSource.VOICE)),
        fi(3, FeedItem.Author.AGENT, Card.Text("Got it. Here’s a new line for that.")),
    ),
    live = "and only for the contract",
    mic = MicChipState.Listening,
    proposal = Amendment(
        AmendmentId("am1"), lineId = LineId("l2"), old = "Never send money or agree to a payment without asking me first.",
        proposed = Line(LineId("l2"), Section.HARD_LINES, "Never send money or agree to pay without asking me. Rescheduling a payment date is fine.", because = "moving a date isn’t a new commitment"),
        reason = "r", status = Amendment.Status.PROPOSED,
        autonomy = AutonomyChange(ActionKind.SEND_EMAIL, AutonomyTier.DO_THEN_TELL),
    ),
)
@Composable private fun Amend(dark: Boolean) = Screen(dark) { AmendContent(amendState, mic, "← back", {}, {}, {}) }
@Composable private fun AmendStart(dark: Boolean) = Screen(dark) {
    AmendContent(AmendUiState(loading = false, session = Session(SessionId("s1"), SessionKind.AMEND, Session.State.ACTIVE, t0)), noMic, "← how I want to work", {}, {}, {})
}
@PreviewTest @Preview(widthDp = 412, heightDp = 1000) @Composable fun AmendLight() = Amend(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 1000) @Composable fun AmendDark() = Amend(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 700) @Composable fun AmendStartLight() = AmendStart(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 700) @Composable fun AmendStartDark() = AmendStart(true)

// ---------- Call ----------

private val callItems = listOf(
    fi(10, FeedItem.Author.AGENT, Card.Text("Morning. Three things came in overnight: Dana’s contract, Nick about Tuesday, and the school field trip form.")),
    fi(11, FeedItem.Author.USER, Card.Said("Let’s do Dana first, the rest can wait till after lunch.", InputSource.VOICE)),
    fi(12, FeedItem.Author.AGENT, Card.Plan(listOf(IfThen("it’s 9:30", "spec at my desk until 12"), IfThen("Dana emails", "it waits until 12"))), Card.Tasks("setting up", listOf(TaskId("t1"), TaskId("t2"), TaskId("t3")))),
)
private val callTasks = listOf(
    task("t1", "Reply to Dana about the revised contract terms", "mail · waits for your review", AgentTask.Status.DRAFTED, "h1"),
    task("t2", "Find a time with Nick for the Devils game", "calendar", AgentTask.Status.WORKING),
    task("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED),
).associateBy { it.id }

private fun call(screen: CallScreen, stage: CallStage? = CallStage.CHOOSE, ended: Boolean = false) = CallUiState(
    screen = screen,
    callTime = "9:00",
    snoozedUntil = "9:15",
    session = Session(SessionId("c1"), SessionKind.MORNING_CALL, if (ended) Session.State.ENDED else Session.State.ACTIVE, t0, endedAt = if (ended) Instant.parse("2026-10-26T14:11:32Z") else null, stage = stage),
    items = callItems,
    tasks = callTasks,
    held = mapOf(emailHeld.id to emailHeld),
    live = if (ended) null else "and remind me to",
    mic = MicChipState.Listening,
)

@Composable private fun Call(dark: Boolean, screen: CallScreen) = Screen(dark) {
    CallContent(call(screen, ended = screen == CallScreen.Ended), mic, {}, {}, {}, {}, {}, {}, {})
}
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallIncomingLight() = Call(false, CallScreen.Incoming)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallIncomingDark() = Call(true, CallScreen.Incoming)

// The incoming-call slider part way through each gesture.
@Composable private fun Slide(dark: Boolean, progress: Float) = Screen(dark) {
    Box(Modifier.padding(24.dp)) { SlideToAnswer(onAnswer = {}, onDecline = {}, initialProgress = progress) }
}
@PreviewTest @Preview(widthDp = 412, heightDp = 140) @Composable fun CallSlideAnsweringLight() = Slide(false, 0.6f)
@PreviewTest @Preview(widthDp = 412, heightDp = 140) @Composable fun CallSlideDecliningDark() = Slide(true, -0.6f)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallSnoozedLight() = Call(false, CallScreen.Snoozed)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallSnoozedDark() = Call(true, CallScreen.Snoozed)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallInCallLight() = Call(false, CallScreen.InCall)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallInCallDark() = Call(true, CallScreen.InCall)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallEndedLight() = Call(false, CallScreen.Ended)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun CallEndedDark() = Call(true, CallScreen.Ended)

// ---------- Main scaffold ----------

@Composable private fun Shell(dark: Boolean) = Screen(dark) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.padding(horizontal = 12.dp)) {
            Snackbar(
                shape = MaterialTheme.shapes.extraSmall,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) { Text("Sent to Dana.") }
        }
        TabBar(MainTab.Held, 2) {}
        TabBar(MainTab.Today, 0) {}
    }
}
@PreviewTest @Preview(widthDp = 412) @Composable fun ShellLight() = Shell(false)
@PreviewTest @Preview(widthDp = 412) @Composable fun ShellDark() = Shell(true)
