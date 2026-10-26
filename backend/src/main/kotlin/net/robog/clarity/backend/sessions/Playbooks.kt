package net.robog.clarity.backend.sessions

import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind

/** One question of the values interview. */
data class InterviewQuestion(
    val theme: String,
    /** What the line is about, for the person ("value", "hard line", ...). */
    val label: String,
    val text: String,
    val section: Section,
    /** Extra guidance for the agent on what this answer should yield. */
    val yields: String,
)

/**
 * Static, per-stage instructions delivered to the lead as an inbox item whenever a session
 * changes state. Pure text: nothing here reads state, so the lead's cached prefix stays stable and the
 * wording is reviewable in one place. The person is listening on a phone: every playbook says to keep it short.
 */
object Playbooks {
    private const val SHORT = "The person is listening on a phone: speak in short, plain sentences, one thing at a time, and never read lists back."
    private const val REACH = "Reach the person only with send_to_user, passing this session's id as `sessionId` so the card belongs to it."

    val QUESTIONS: List<InterviewQuestion> = listOf(
        InterviewQuestion(
            "when help worked", "value",
            "Think of a time someone took something off your plate and got it exactly right. What did they do?",
            Section.VALUES,
            "One line about what good help looks like, with the reason in the person's own words.",
        ),
        InterviewQuestion(
            "when help backfired", "hard line",
            "Now a time help made things worse. What crossed the line?",
            Section.HARD_LINES,
            "One hard line: something never to do. Set hard=true.",
        ),
        InterviewQuestion(
            "what's hard to undo", "how much I do alone",
            "What would be hard to undo if I got it wrong?",
            Section.AUTONOMY,
            "One line, plus the autonomy tiers (do it then tell me / ask me first / never alone), each with the examples the person gave, plus anything they never want done at all (never). Pass them as `tiers`. Don't assume: if they didn't say how to treat money or sending as them, ask once.",
        ),
        InterviewQuestion(
            "a good day", "value",
            "Walk me through a good Tuesday. What's protected?",
            Section.VALUES,
            "One line about what to protect. Remember the protected time blocks (days, start, end) for the final constitution.",
        ),
        InterviewQuestion(
            "when things collide", "priority",
            "Say Priya needs you during your focus block. Which wins?",
            Section.COLLISIONS,
            "One line about which wins when two things collide, and when to ask.",
        ),
        InterviewQuestion(
            "who gets through", "people",
            "Who should always be able to reach you, even mid-focus?",
            Section.PEOPLE,
            "One line naming the people who get through. Remember them (names, and handles if known) for the final constitution.",
        ),
        InterviewQuestion(
            "when I get it wrong", "repair + memory",
            "I'll get things wrong sometimes. When I do, what do you want from me? And is there anything I shouldn't remember?",
            Section.REPAIR,
            "One line about how to repair a mistake. Remember any topics never to store for the final constitution.",
        ),
    )

    fun morningCall(stage: CallStage): String = when (stage) {
        CallStage.CLEAR -> """
            Morning call started, stage CLEAR (empty the person's head). $SHORT $REACH
            1. Greet them in one line and ask "What's on your mind? Say everything." Then call stage_done so they can speak and move on.
            2. As they talk, for each thing they mention that could be in their records (a person, a contract, a meeting), look it up with the domain tools
               and show ONE context card per lookup (a `context` card: label like "Dana's contract · 2 found", items with a tag like "mail · yesterday").
               Do not narrate lookups out loud.
            3. Call capture with everything they said, as short items. Call it again as more comes up; it updates the same card.
            Nothing is decided in this stage. Do not prioritise yet.
        """.trimIndent()

        CallStage.CHOOSE -> """
            Stage CHOOSE. $SHORT $REACH
            Ask once: "Ignoring deadlines, which one would you regret not moving forward?" Optionally ask for at most ONE more that must happen today.
            Then call set_priorities(important, also?) with their answer (a short note like "important" or "due today" for each) and finish with stage_done.
            Never choose for them. Three or more priorities is the opposite of this stage: gently hold it to two.
        """.trimIndent()

        CallStage.COMMIT -> """
            Stage COMMIT (implementation intentions). $SHORT $REACH
            For the priorities: ask when and where they will do it, and what is the likeliest thing to get in the way. Check their calendar for that time
            with list_calendar_events before agreeing; if it collides, say so and ask for another time.
            Turn each answer into an if-then ("If it's 9:30, then spec at my desk until 12", "If Dana emails, then it waits until 12") and call
            set_plan(ifThens). Then stage_done.
        """.trimIndent()

        CallStage.HAND_OFF -> """
            Stage HAND_OFF. $SHORT $REACH
            Ask: "What can I do to help clear the way?" From what they say (and the plan), decide what you can do alone, what only you can prepare for
            their review, and what to park until tomorrow. Say plainly: "Nothing goes out as you without your review."
            Call hand_off(alone, review, parked) once: it creates your tasks and shows the person "I'll do" and "parked until tomorrow".
            Then call stage_done. The actions themselves are NOT done in the call: after the call ends you carry them out with the usual tools (holds, drafts, reminders);
            the guard decides what is held for review.
        """.trimIndent()
    }

    fun callEnded(reachedHandOff: Boolean): String =
        if (reachedHandOff) {
            """
            The morning call ended after the hand-off. Now carry it out quietly: for each task you created in hand_off, do it with the usual tools
            (create_event for holds, save_draft for replies, create_reminder for nudges, set_do_not_disturb for muting, ...), keeping update_task honest as
            each moves to done, drafted or waiting_for_you. Do not message the person unless something needs them or fails. Write one journal line about the day's plan.
            """.trimIndent()
        } else {
            "The morning call ended before the hand-off. Nothing to carry out. If they named priorities, write one journal line; say nothing to the person."
        }

    fun callSnoozed(minutes: Long): String =
        "The person declined the morning call and will be called again in $minutes minutes. Say nothing and do nothing now."

    const val CALL_RECALLED: String =
        "The snoozed morning call is ringing again, back at stage CLEAR. Greet them when they pick up, as in stage CLEAR: one line, then stage_done."

    fun valuesQuestion(index: Int): String {
        val q = QUESTIONS[index]
        val first = if (index == 0) {
            "The values interview has started. You are writing the person's constitution with them, in their own words, from seven questions. " +
                "Open with one warm sentence, say there are seven questions and no wrong answers. "
        } else {
            ""
        }
        return """
            ${first}Question ${index + 1} of ${QUESTIONS.size}: ${q.theme} (${q.label}). $SHORT $REACH
            Ask exactly this, in your own voice: "${q.text}" Then call stage_done so they can answer.
            When they have answered, write ONE line in the first person that captures it, keeping their words and their reason, and call
            propose_line(section=${q.section.name.lowercase()}, text, because). ${q.yields}
            The line shows as a "captured as" card; they can say "reword" (you will be asked for another phrasing) or "keep" (the next question follows).
            Do not read the line aloud. Do not ask a second question in the same turn.
        """.trimIndent()
    }

    const val VALUES_REWORD: String =
        "The person asked to reword the line you captured for this question. Propose a different phrasing of the same meaning with propose_line " +
            "(same section, same reason unless they gave a new one). Do not ask anything."

    const val VALUES_FINALIZE: String =
        "All seven questions are answered and kept. Write the constitution now: call finalize_constitution once with the autonomy tiers by action kind, " +
            "who gets through, the protected time blocks, topics never to store, and a few short profile facts you learned. Add fixed lines only if the person " +
            "said them (for example 'never move money'). When it succeeds, tell them in one or two sentences that their constitution is ready and what it says about what you may do alone."

    fun amend(heldSummary: String?): String = buildString {
        append("Amend session started: the person wants to change one of their rules by talking. ")
        append(SHORT).append(' ').append(REACH).append('\n')
        if (heldSummary != null) {
            append("It started from an action you tried that hit a rule: ").append(heldSummary).append('\n')
        }
        append(
            "Ask which rule and what they want instead, and why, in their words. Read the constitution in your prompt; do not guess a line id. " +
                "Hard lines cannot be changed by talking. When you know, call propose_amendment(lineId?, text, because, reason); it appears for them to save or discard. " +
                "Say in one sentence what it changes. The session ends when they save or discard it.",
        )
    }

    fun amendEnded(): String = "The amend session ended. Nothing further to do unless a rule changed (the inbox will tell you)."

    const val INTERVIEW_ENDED: String =
        "The values interview ended. If a constitution was written you have the new text in your prompt; otherwise nothing changed."

    fun preparation(): String =
        "Prepare for the morning call that starts in about ten minutes, silently: read yesterday's journal and today's, check yesterday's plan and what was parked " +
            "(Today), look at today's calendar and new mail since yesterday evening, and leave yourself a short scratch note with the three things worth knowing. " +
            "Do NOT message the person or call send_to_user."

    /** The note delivered with a transition; null when the lead has nothing to do. */
    fun forTransition(before: SessionRecord?, after: SessionRecord, control: SessionControl?, heldSummary: String? = null): String? {
        val session = after.session
        return when (after.kind) {
            SessionKind.MORNING_CALL -> when {
                before == null -> session.stage?.let(::morningCall)
                control is SessionControl.Snooze -> callSnoozed(control.minutes.toLong())
                after.state == Session.State.ENDED -> callEnded(reachedHandOff = before.session.stage == CallStage.HAND_OFF)
                before.state == Session.State.SNOOZED -> CALL_RECALLED
                else -> session.stage?.takeIf { it != before.session.stage }?.let(::morningCall)
            }
            SessionKind.VALUES_INTERVIEW -> when {
                before == null -> valuesQuestion(0)
                after.state == Session.State.ENDED -> INTERVIEW_ENDED
                after.finalizing -> VALUES_FINALIZE
                control == SessionControl.Reword -> VALUES_REWORD
                session.question != before.session.question -> session.question?.let(::valuesQuestion)
                else -> null
            }
            SessionKind.AMEND -> when {
                before == null -> amend(heldSummary)
                after.state == Session.State.ENDED -> amendEnded()
                else -> null
            }
        }
    }
}
