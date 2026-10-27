package net.robog.clarity.app.data

import kotlin.coroutines.cancellation.CancellationException
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.connector.ConnectorWork
import net.robog.clarity.app.connector.DndActionHandler
import net.robog.clarity.app.connector.SmsCursorStore
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.push.NotifiedLedger
import net.robog.clarity.app.push.PushFallbackWork
import net.robog.clarity.app.push.PushNotifier
import net.robog.clarity.app.vault.VaultKeys

/** One piece of what this phone keeps about the server it is paired with, and how to let go of it. */
class ServerScopedState(val name: String, val forget: suspend () -> Unit)

/**
 * Everything kept for the paired server besides the session, the feed cache and the device key
 * (which [DefaultSessionRepository] owns), in the order it is let go of: first the background work,
 * so nothing writes new state for the old server while the rest is cleared.
 *
 * Not on the list, on purpose: the person's own push switch and whether the battery question was
 * asked (choices about this phone, not about the server). The push service itself stops on its
 * own once the session is gone (it follows `PushConditions`), and the caller reconciles it too.
 */
fun perServerState(
    sync: FeedSync,
    pushWork: PushFallbackWork,
    connectorWork: ConnectorWork,
    callScheduler: CallScheduler,
    dnd: DndActionHandler,
    notifier: PushNotifier,
    flag: OnboardingFlagStore,
    ledger: NotifiedLedger,
    smsCursor: SmsCursorStore,
    vaultKeys: VaultKeys,
): List<ServerScopedState> = listOf(
    // The 15-minute catch-up and the phone connector's pass would talk to (and write for) the old server.
    ServerScopedState("push fallback job") { pushWork.cancel() },
    ServerScopedState("phone connector job") { connectorWork.cancel() },
    // "Thinking…" or "unauthorized" from the old server must not show on the next one.
    ServerScopedState("feed connection state") { sync.forgetServer() },
    // Its morning call must not ring, and must not come back from the cached schedule after a reboot.
    ServerScopedState("call alarms and cached settings") { callScheduler.forgetServer() },
    // A do-not-disturb window it asked for ends now: nobody would manage it any more.
    ServerScopedState("do not disturb window") { dnd.endNow() },
    // Its notifications link into a feed that is gone.
    ServerScopedState("notifications") { notifier.clearAll() },
    // Set up again with the next server, step by step (PushConditions.setUp follows this).
    ServerScopedState("onboarding") { flag.forgetServer() },
    // Seqs, held ids and releases of the old server mean nothing on the next one.
    ServerScopedState("notified ledger") { ledger.reset() },
    // The next server has none of these texts: send them all again, and "last synced" starts over.
    ServerScopedState("SMS cursor") { smsCursor.clear() },
    // What the old server sealed to these keys is gone with it; the next server gets new keys.
    ServerScopedState("vault keys") { vaultKeys.delete() },
)

/** Forgets each piece in turn; one that fails does not keep the others. Returns the names that failed. */
suspend fun forgetAll(states: List<ServerScopedState>): List<String> = states.mapNotNull { state ->
    try {
        state.forget()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        state.name
    }
}
