package net.robog.clarity.backend.vault

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.LocalOutcome
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.vault.CredentialMeta

/** What the browser wrapper does next after asking governance about something. */
sealed interface Hold {
    /** Go ahead (an earlier approval of exactly this was found and used up, or the guard allowed it alone). */
    data object Proceed : Hold

    /** Stop and tell the agent [text]; [heldId] is the item the person will decide, when there is one. */
    data class Wait(val text: String, val heldId: HeldActionId? = null) : Hold
}

/**
 * The browser wrapper's and the vault tools' seam to governance: held approvals for credential use,
 * two-factor codes and risky browser actions. Faked in wrapper tests.
 */
interface VaultGovernance {
    /** Ask the person to release [credential] for [domain] (held, approved only on the phone). */
    suspend fun useCredential(credential: CredentialMeta, domain: String, pageUrl: String, provenance: Provenance, reason: String): Hold

    /** Ask the person for a two-factor code for [site]. Always [Hold.Wait] with the held id the code will arrive under. */
    suspend fun askCode(site: String, purpose: String, provenance: Provenance): Hold.Wait

    /**
     * An acting browser step the rules or the triage want the person to see. Returns [Hold.Proceed]
     * when the same step was approved earlier (consumed here, so one yes is one step), else holds it.
     */
    suspend fun browserStep(action: ProposedAction.ExternalTool, provenance: Provenance, reason: String, context: List<ContextItem>): Hold

    /**
     * Waits until the person decides [heldId] or [timeout] passes. Returns the status it settled
     * in (an item that is not simply held, such as a blocked one, returns at once), or null when
     * the time ran out.
     */
    suspend fun awaitDecision(heldId: HeldActionId, timeout: Duration): HeldAction.Status? = null
}

private val RECENT_DECLINE = 10.minutes

private val DECISION_POLL = 3.seconds

/** How long the person's yes to a browser step waits for the agent to take it. */
internal val APPROVAL_LASTS = 30.minutes

private val UID_KEYS = setOf("uid", "fromUid", "toUid")

/** Pure: [element] without snapshot uids, which change every time the agent looks at the page again. */
internal fun withoutUids(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { it !in UID_KEYS }.mapValues { withoutUids(it.value) })
    is JsonArray -> JsonArray(element.map(::withoutUids))
    else -> element
}

/**
 * Pure: whether two browser steps are the same step. The summary names the element and page
 * ("Click button \"Sign in\" on www.ulta.com/u/login", or "Click element e46" when it has no label),
 * so the uid itself is left out: after a fresh snapshot the same button has a new uid.
 */
internal fun sameBrowserStep(a: ProposedAction.ExternalTool, b: ProposedAction.ExternalTool): Boolean =
    a.server == b.server && a.tool == b.tool && a.summary == b.summary && withoutUids(a.input) == withoutUids(b.input)

/** [VaultGovernance] over the action gate, held-action store and feed. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class GovernedVaultHolds(
    private val gate: ActionGate,
    private val held: HeldActionStore,
    private val clock: Clock,
    private val events: GovernanceEvents,
) : VaultGovernance {
    override suspend fun awaitDecision(heldId: HeldActionId, timeout: Duration): HeldAction.Status? = withTimeoutOrNull(timeout) {
        suspend fun now() = held.get(heldId)?.status ?: HeldAction.Status.EXPIRED
        var status = now()
        while (status == HeldAction.Status.HELD) {
            // Woken by the decision event; the poll is the fallback if an event was dropped.
            withTimeoutOrNull(DECISION_POLL) { events.events.first { it is GovernanceEvent.HeldChanged && it.held.id == heldId } }
            status = now()
        }
        status
    }

    /**
     * Looking for an open identical proposal and holding a new one happen under one lock: the guard
     * takes seconds, and the agent often fills a login's fields in parallel calls, which otherwise
     * both found nothing pending and asked twice.
     */
    private val proposing = Mutex()

    override suspend fun useCredential(
        credential: CredentialMeta,
        domain: String,
        pageUrl: String,
        provenance: Provenance,
        reason: String,
    ): Hold = proposing.withLock {
        val same = { a: ProposedAction -> a is ProposedAction.UseCredential && a.credentialId == credential.id && a.site == domain }
        pendingOrDeclined(same, "using ${credential.name} on $domain")?.let { return@withLock it }
        val differs = Sites.registrableDomain(credential.site)?.let { it != domain } ?: false
        val summary = "Use ${credential.name} (${credential.fields.joinToString { it.name }}) on $domain for this task (up to an hour)" +
            if (differs) " [saved for ${credential.site}, not $domain]" else ""
        val action = ProposedAction.UseCredential(
            idempotencyKey = "vault:use:${Uuid.random()}",
            summary = summary,
            credentialId = credential.id,
            site = domain,
            fields = credential.fields.map { it.name },
        )
        hold(action, reason.ifBlank { credential.purpose }, listOf(ContextItem("page", pageUrl), ContextItem("saved for", credential.site)), provenance, "push_now")
    }

    /**
     * A code request still waiting for the person for the same site is reused (same id, so the same
     * placeholder): asking again would send them a second urgent notification for one code.
     */
    override suspend fun askCode(site: String, purpose: String, provenance: Provenance): Hold.Wait = proposing.withLock {
        val domain = Sites.registrableDomain(site) ?: site
        held.list(setOf(HeldAction.Status.HELD), limit = 200)
            .firstOrNull { (it.action as? ProposedAction.AskCode)?.site == domain }
            ?.let { return@withLock Hold.Wait("Already asked the person for the $domain code; it is still waiting for them. Do not ask again.", it.id) }
        val action = ProposedAction.AskCode(
            idempotencyKey = "vault:code:${Uuid.random()}",
            summary = "Type the code $domain sent you",
            site = domain,
            purpose = purpose,
        )
        when (val result = hold(action, purpose, listOf(ContextItem("site", domain)), provenance, "push_now")) {
            is Hold.Wait -> result
            Hold.Proceed -> Hold.Wait("Could not ask for the code.")
        }
    }

    override suspend fun browserStep(
        action: ProposedAction.ExternalTool,
        provenance: Provenance,
        reason: String,
        context: List<ContextItem>,
    ): Hold = proposing.withLock {
        val same = { a: ProposedAction -> a is ProposedAction.ExternalTool && sameBrowserStep(a, action) }
        val fresh = clock.now() - APPROVAL_LASTS
        held.list(setOf(HeldAction.Status.APPROVED), limit = 200).firstOrNull { same(it.action) && (it.decidedAt ?: fresh) > fresh }?.let { approved ->
            if (held.transition(approved.id, setOf(HeldAction.Status.APPROVED), HeldAction.Status.EXECUTED)) return@withLock Hold.Proceed
        }
        pendingOrDeclined(same, "that step")?.let { return@withLock it }
        // The helper waits for this answer, so it notifies like a login does.
        hold(action.copy(idempotencyKey = "browser:${Uuid.random()}"), reason, context, provenance, "push_now")
    }

    /** A still-open or just-declined identical proposal, as the answer to give instead of asking again. */
    private suspend fun pendingOrDeclined(same: (ProposedAction) -> Boolean, what: String): Hold.Wait? {
        held.list(setOf(HeldAction.Status.HELD, HeldAction.Status.BLOCKED), limit = 200).firstOrNull { same(it.action) }?.let {
            return Hold.Wait("Still waiting for the person to decide $what. Do not ask again; carry on with something else.", it.id)
        }
        val cutoff = clock.now() - RECENT_DECLINE
        held.list(setOf(HeldAction.Status.DECLINED), limit = 200).firstOrNull { same(it.action) && (it.decidedAt ?: cutoff) > cutoff }?.let {
            return Hold.Wait("The person declined $what. Do not try again unless they ask you to; tell them what you could not do.", it.id)
        }
        return null
    }

    private suspend fun hold(
        action: ProposedAction,
        reason: String,
        context: List<ContextItem>,
        provenance: Provenance,
        notify: String,
    ): Hold {
        val urgency = if (notify == "push_now") Urgency.URGENT else Urgency.NORMAL
        return when (val outcome = gate.gateLocal(action, reason, context, provenance, urgency)) {
            is LocalOutcome.Cleared ->
                // Vault kinds can never be cleared alone (they are never-alone kinds); a browser step the guard allows alone just goes ahead.
                if (action is ProposedAction.ExternalTool) Hold.Proceed else Hold.Wait("Needs the person: ${action.summary}.")
            // Held items wait in the person's Held list (an urgent one also notifies). They reach the
            // chat only when the agent posts a proposal card with send_to_user.
            is LocalOutcome.Held -> Hold.Wait(waitingText(action, outcome.text) + IN_HELD_LIST, outcome.heldId)
            is LocalOutcome.Blocked -> Hold.Wait("${outcome.text} Do not retry; tell the person what you could not do.", outcome.heldId)
        }
    }

    private fun waitingText(action: ProposedAction, base: String): String = when (action) {
        is ProposedAction.UseCredential ->
            "$base The person has to approve on their phone (fingerprint or face). Do not retry now; carry on with something else, and repeat the same step after you are told it was approved."
        is ProposedAction.AskCode -> base
        else ->
            "$base Do not retry now; carry on with something else. When you are told it was approved, take the same step on the same element " +
                "(a fresh snapshot is fine); the approval lasts ${APPROVAL_LASTS.inWholeMinutes} minutes."
    }
}

/** Where a held item waits, and how to bring it into the chat. */
private const val IN_HELD_LIST =
    " It waits in the person's Held list; it is not in the chat. Post a proposal card (heldActionId) with send_to_user only if they should see it there now."
