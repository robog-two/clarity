package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role

/**
 * Overrides the lead's effort while something time-critical is going on (a live morning call,
 * where the person waits for every answer). Null means "the role's own effort". The sessions
 * workstream replaces the default binding.
 */
fun interface EffortOverride {
    suspend fun current(): String?
}

@Inject
@ContributesBinding(AppScope::class)
class NoEffortOverride : EffortOverride {
    override suspend fun current(): String? = null
}

/** Pure: the effort the thread last asked for with a mid-conversation message, if any. */
fun lastEffort(history: List<Message>): String? =
    history.lastOrNull { it.role == Role.SYSTEM && it.effort != null }?.effort

/**
 * Pure: the effort-only message that moves the thread from [applied] to [desired], or null when
 * they already agree. [applied] is the last effort set in the journal, else the role's own effort.
 */
fun effortChange(applied: String, desired: String): Message? = if (applied == desired) null else Message.effort(desired)
