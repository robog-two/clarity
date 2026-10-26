package net.robog.clarity.backend.sessions

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.StartSession

private val log = KotlinLogging.logger {}

/**
 * Runs sessions: validates and stores each transition (the pure machines in `SessionMachine.kt`),
 * tells the app (`FeedEvent.SessionChanged`) and hands the lead the playbook for the new step as an
 * inbox item. Only one session is live at a time; starting another ends the previous one.
 */
@Inject
@SingleIn(AppScope::class)
class SessionService(
    private val store: SessionStore,
    private val feed: FeedService,
    private val inbox: InboxStore,
    private val held: HeldActionStore,
    private val constitutions: ConstitutionStore,
    private val clock: Clock,
) {
    suspend fun start(request: StartSession): Either<SessionError, Session> {
        val now = clock.now()
        if (request.heldActionId != null && request.kind != SessionKind.AMEND) {
            return SessionError.Invalid("heldActionId only applies to an amend session").left()
        }
        val heldAction = request.heldActionId?.let { held.get(it) ?: return SessionError.Invalid("no held action '${it.value}'").left() }
        if (request.kind == SessionKind.VALUES_INTERVIEW && constitutions.current() != null) {
            return SessionError.Illegal("a constitution already exists; change a rule with an amend session").left()
        }
        val record = startRecord(SessionId(Uuid.random().toString()), request.kind, now, request.heldActionId)
        val (started, replaced) = store.begin(record, now)
        replaced?.let { announce(it, it.closedView(now), null, quiet = true) }
        val summary = heldAction?.let { "\"${it.action.summary}\" (${it.verdict.rationale})" }
        announce(null, started, null, heldSummary = summary)
        return started.session.right()
    }

    suspend fun control(id: SessionId, control: SessionControl): Either<SessionError, Session> {
        val now = clock.now()
        return mutate(id, control) { it.control(control, now) }
    }

    /** Applies a pure transition and announces it; [quiet] skips the playbook for the lead. */
    suspend fun mutate(
        id: SessionId,
        control: SessionControl? = null,
        quiet: Boolean = false,
        change: (SessionRecord) -> Either<SessionError, SessionRecord>,
    ): Either<SessionError, Session> =
        store.mutate(id, change).map { (before, after) ->
            announce(before, after, control, quiet = quiet)
            after.session
        }

    /** Changes only the working data of a session (captured chips, a hand-off marker): no transition, no playbook. */
    suspend fun updateData(id: SessionId, change: (SessionData) -> SessionData): Either<SessionError, SessionRecord> =
        store.mutate(id) { it.copy(data = change(it.data)).right() }.map { (_, after) -> after }

    suspend fun get(id: SessionId): Either<SessionError, Session> =
        store.get(id)?.let { resolve(it).session.right() } ?: SessionError.NotFound(id.value).left()

    /** The live session, after settling an amend session whose amendment has been decided. */
    suspend fun live(): SessionRecord? = store.live()?.let { resolve(it) }?.takeIf { it.live }

    suspend fun active(): List<Session> = listOfNotNull(live()?.session)

    suspend fun recent(limit: Int = 20): List<Session> = store.recent(limit).map { resolve(it).session }

    /** Rings snoozed calls that are due; returns how many. */
    suspend fun recallDue(): Int {
        val now = clock.now()
        var recalled = 0
        for (due in store.snoozedDue(now)) {
            val result = mutate(due.id, null) { if (it.recallDue(now)) it.recalled().right() else SessionError.NotActive(it.state).left() }
            if (result.isRight()) recalled++
        }
        return recalled
    }

    /** Ends the live amend session when its amendment was saved or discarded. */
    suspend fun sweep() {
        store.live()?.let { resolve(it) }
    }

    /**
     * An amend session ends when its amendment is no longer proposed (the person saved or discarded
     * it through the governance routes, which know nothing about sessions).
     */
    private suspend fun resolve(record: SessionRecord): SessionRecord {
        val amendmentId = record.session.amendmentId
        if (record.kind != SessionKind.AMEND || !record.live || amendmentId == null) return record
        val amendment = constitutions.amendment(amendmentId) ?: return record
        if (amendment.status == Amendment.Status.PROPOSED) return record
        val now = clock.now()
        return store.mutate(record.id) { it.closed(now).right() }.fold(
            { record },
            { (before, after) -> if (before.live) announce(before, after, null) ; after },
        )
    }

    private suspend fun announce(
        before: SessionRecord?,
        after: SessionRecord,
        control: SessionControl?,
        quiet: Boolean = false,
        heldSummary: String? = null,
    ) {
        feed.publish(FeedEvent.SessionChanged(after.id))
        if (quiet) return
        val note = Playbooks.forTransition(before, after, control, heldSummary) ?: return
        try {
            inbox.enqueue(InboxItem.SessionEvent(after.id, note), dedupKey = "session:${after.id.value}:${after.version}")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "could not queue the playbook for session ${after.id}" }
        }
    }
}

private fun SessionRecord.closedView(now: kotlin.time.Instant): SessionRecord = closed(now)
