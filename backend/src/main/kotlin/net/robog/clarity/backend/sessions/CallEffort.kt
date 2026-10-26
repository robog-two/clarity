package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.robog.clarity.backend.agent.EffortOverride
import net.robog.clarity.backend.agent.NoEffortOverride
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionKind

/** The effort the lead runs at during a live call, where the person waits for every answer. */
const val CALL_EFFORT = "low"

/** Pure: the effort override for the [live] session (a morning call in progress), else none. */
fun callEffort(live: SessionRecord?): String? =
    if (live?.kind == SessionKind.MORNING_CALL && (live.state == Session.State.ACTIVE || live.state == Session.State.BUSY)) CALL_EFFORT else null

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoEffortOverride::class])
class CallEffortOverride(private val sessions: SessionService) : EffortOverride {
    override suspend fun current(): String? = callEffort(sessions.live())
}
