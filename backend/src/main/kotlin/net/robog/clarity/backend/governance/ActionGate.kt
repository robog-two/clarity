package net.robog.clarity.backend.governance

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.context.raise
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import arrow.core.raise.Raise
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.payloadFor
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.Urgency
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** What an external domain tool gets back from [ActionGate.propose]; [text] is for the model. */
sealed interface ProposalOutcome {
    val text: String

    /** Queued for the connector; the person is told afterwards. */
    data class Allowed(val actionId: ActionId, override val text: String) : ProposalOutcome

    /** Waiting for the person. */
    data class Held(val heldId: HeldActionId, override val text: String) : ProposalOutcome

    /** Hit a hard line (or could not be routed or was declined); the person can still review it. */
    data class Blocked(val heldId: HeldActionId, override val text: String) : ProposalOutcome
}

/**
 * What [ActionGate.gateLocal] decided for an effect the backend performs itself (a browser action,
 * releasing a credential): nothing is queued for a connector.
 */
sealed interface LocalOutcome {
    val text: String

    /** The guard let it through alone; the caller does it. */
    data class Cleared(override val text: String) : LocalOutcome

    /** Waiting for the person. */
    data class Held(val heldId: HeldActionId, override val text: String) : LocalOutcome

    /** Hit a hard line; the person can still review it. */
    data class Blocked(val heldId: HeldActionId, override val text: String) : LocalOutcome
}

/** The person's time zone, for protected-time checks; replaced when settings exist. */
fun interface PersonZone {
    suspend fun zone(): TimeZone
}

@Inject
@ContributesBinding(AppScope::class)
class UtcPersonZone : PersonZone {
    override suspend fun zone(): TimeZone = TimeZone.UTC
}

/** The constitution used before the values interview has produced one: everything is "ask first". */
fun emptyConstitution(now: kotlin.time.Instant) = Constitution(version = 0, lines = emptyList(), updatedAt = now)

/**
 * The single door through which agents act on the world. Domain tools with external effects call
 * [propose]: the guard decides, and the action is queued, held or blocked. Idempotent by the
 * action's key: a retried run gets the original outcome back.
 */
@Inject
@SingleIn(AppScope::class)
class ActionGate(
    private val guard: Guard,
    private val constitutions: ConstitutionStore,
    private val held: HeldActionStore,
    private val outbox: ActionOutbox,
    private val router: ConnectorRouter,
    private val events: GovernanceEvents,
    private val zone: PersonZone,
    private val clock: Clock,
) {
    suspend fun propose(
        action: ProposedAction,
        agentView: String,
        context: List<ContextItem>,
        provenance: Provenance,
        urgency: Urgency,
    ): ProposalOutcome {
        existing(action.idempotencyKey)?.let { return it }
        repeated(action)?.let { return it }

        val constitution = constitutions.current() ?: emptyConstitution(clock.now())
        val verdict = guard.check(action, constitution, provenance, agentReason = agentView, zone = zone.zone())

        if (verdict.decision == Decision.ALLOW) {
            val connector = router.select(action.kind)
                ?: return unroutable(action, verdict, agentView, context, urgency)
            val id = outbox.enqueue(connector, action, alone = true)
            return ProposalOutcome.Allowed(id, "Done: ${action.summary} (queued for $connector; the person is told afterwards).")
        }

        val status = if (verdict.decision == Decision.DENY) HeldAction.Status.BLOCKED else HeldAction.Status.HELD
        val stored = held.insert(action, verdict, agentView, context, urgency, status)
        events.publish(GovernanceEvent.HeldChanged(stored))
        return outcomeFor(stored)
    }

    /**
     * Like [propose] for effects the backend performs itself (browser actions, vault releases):
     * the guard decides; ALLOW returns [LocalOutcome.Cleared] for the caller to act on, anything else
     * is held for the person and nothing reaches a connector. [action] must carry a fresh key per attempt.
     */
    suspend fun gateLocal(
        action: ProposedAction,
        agentView: String,
        context: List<ContextItem>,
        provenance: Provenance,
        urgency: Urgency,
    ): LocalOutcome {
        val constitution = constitutions.current() ?: emptyConstitution(clock.now())
        val verdict = guard.check(action, constitution, provenance, agentReason = agentView, zone = zone.zone())
        if (verdict.decision == Decision.ALLOW) return LocalOutcome.Cleared("Allowed alone: ${action.summary}.")
        val status = if (verdict.decision == Decision.DENY) HeldAction.Status.BLOCKED else HeldAction.Status.HELD
        val stored = held.insert(action, verdict, agentView, context, urgency, status)
        events.publish(GovernanceEvent.HeldChanged(stored))
        return when (stored.status) {
            HeldAction.Status.BLOCKED -> LocalOutcome.Blocked(stored.id, "Blocked: ${stored.action.summary}. ${stored.verdict.rationale} The person can still review it.")
            else -> LocalOutcome.Held(stored.id, "Waiting for the person: ${stored.action.summary}. ${stored.verdict.rationale}")
        }
    }

    private suspend fun unroutable(
        action: ProposedAction,
        verdict: net.robog.clarity.shared.governance.GuardVerdict,
        agentView: String,
        context: List<ContextItem>,
        urgency: Urgency,
    ): ProposalOutcome {
        val why = "No connector can do \"${action.kind.label()}\" yet."
        val stored = held.insert(action, verdict, agentView, context, urgency, HeldAction.Status.FAILED, failure = why)
        events.publish(GovernanceEvent.HeldChanged(stored))
        return ProposalOutcome.Blocked(stored.id, "Not done: $why Tell the person what you wanted to do instead.")
    }

    /** The outcome of an earlier proposal with the same key, if any. */
    private suspend fun existing(key: String): ProposalOutcome? {
        outbox.findByKey(key)?.let { return ProposalOutcome.Allowed(it.id, "Already queued: ${it.action.summary}.") }
        return held.findByKey(key)?.let(::outcomeFor)
    }

    /**
     * The same action ([actionFingerprint]) the same run or thread proposed in the last
     * [REPEAT_WINDOW] that is queued, sent, waiting for the person or approved: a repeat (a later
     * step or turn sending the same email again) gets that one back instead of acting twice. An
     * edited version, a declined or failed one, and another run's are not repeats.
     */
    private suspend fun repeated(action: ProposedAction): ProposalOutcome? {
        val print = actionFingerprint(action) ?: return null
        val prefix = runKeyPrefix(action.idempotencyKey) ?: return null
        val since = clock.now() - REPEAT_WINDOW
        outbox.recentByKeyPrefix(prefix, since).firstOrNull { actionFingerprint(it.action) == print }?.let {
            val done = it.status == OutboxStatus.DONE
            return ProposalOutcome.Allowed(it.id, "Already ${if (done) "sent" else "queued"} as ${it.id}: ${it.action.summary}. Not sent again.")
        }
        held.recentByKeyPrefix(prefix, since, REPEATABLE_HELD).firstOrNull { (h, original) ->
            actionFingerprint(h.action) == print || original?.let(::actionFingerprint) == print
        }?.let { (h, _) ->
            return when (h.status) {
                HeldAction.Status.HELD -> ProposalOutcome.Held(h.id, "Already waiting for the person as ${h.id}: ${h.action.summary}. Don't propose it again; they will decide.")
                else -> ProposalOutcome.Held(h.id, "Already approved by the person as ${h.id}: ${h.action.summary}. Not sent again.")
            }
        }
        return null
    }

    private fun outcomeFor(h: HeldAction): ProposalOutcome = when (h.status) {
        HeldAction.Status.HELD ->
            ProposalOutcome.Held(h.id, "Held for review: ${h.action.summary}. ${h.verdict.rationale} Don't retry; the person will decide.")
        HeldAction.Status.BLOCKED ->
            ProposalOutcome.Blocked(h.id, "Blocked: ${h.action.summary}. ${h.verdict.rationale} The person can still review it.")
        HeldAction.Status.APPROVED, HeldAction.Status.EXECUTED ->
            ProposalOutcome.Held(h.id, "Already approved by the person: ${h.action.summary}.")
        HeldAction.Status.FAILED ->
            ProposalOutcome.Blocked(h.id, "Not done: ${h.action.summary} failed earlier. ${h.verdict.rationale}")
        HeldAction.Status.DECLINED, HeldAction.Status.KEPT, HeldAction.Status.RULE_UPDATED, HeldAction.Status.EXPIRED ->
            ProposalOutcome.Blocked(h.id, "Not done: the person already ruled on ${h.action.summary} (${h.status.name.lowercase()}). Don't propose it again.")
    }
}

/** Held statuses in which a proposal is still standing: an identical one from the same run is a repeat. */
private val REPEATABLE_HELD = setOf(HeldAction.Status.HELD, HeldAction.Status.APPROVED, HeldAction.Status.EXECUTED)

/** Decisions on held actions (`POST /api/v1/held/{id}/decision`) and listing. */
@Inject
@SingleIn(AppScope::class)
class HeldActionService(
    private val database: Database,
    private val clock: Clock,
    private val store: HeldActionStore,
    private val constitutions: ConstitutionStore,
    private val outbox: ActionOutbox,
    private val router: ConnectorRouter,
    private val events: GovernanceEvents,
    private val vault: net.robog.clarity.backend.vault.VaultStore,
) {
    suspend fun list(statuses: Set<HeldAction.Status>? = null): List<HeldAction> = store.list(statuses)

    suspend fun get(id: HeldActionId): Either<GovernanceError, HeldAction> = either {
        ensureNotNull(store.get(id)) { GovernanceError.HeldNotFound(id.value) }
    }

    /**
     * Decline and keep mark the status. Approve (with an optionally edited action of the same kind)
     * is re-checked against hard lines only, then queued for the connector that handles it; if
     * none does, the held action fails with that message and nothing is queued. Actions the person
     * set to "never alone" (and vault actions) are refused here: they need [decideSigned].
     */
    suspend fun decide(id: HeldActionId, decision: HeldDecision): Either<GovernanceError, HeldAction> = either {
        val current = ensureNotNull(store.get(id)) { GovernanceError.HeldNotFound(id.value) }
        ensure(current.status in PENDING_STATUSES) { GovernanceError.HeldNotPending(id.value, current.status.name.lowercase()) }
        when (decision) {
            HeldDecision.Decline -> ensure(finish(current, HeldAction.Status.DECLINED)) { GovernanceError.HeldNotPending(id.value, "decided") }
            HeldDecision.Keep -> ensure(finish(current, HeldAction.Status.KEPT)) { GovernanceError.HeldNotPending(id.value, "decided") }
            is HeldDecision.Approve -> {
                val action = decision.edited ?: current.action
                ensure(action.kind == current.action.kind) { GovernanceError.InvalidEdit("an edit cannot change the kind of action") }
                // Vault actions are approved only on the phone, with the secret (see vault/VaultService).
                ensure(action.kind !in VAULT_KINDS) { GovernanceError.ApproveElsewhere("this one is approved on your phone's vault screen, with your fingerprint or face") }
                // Anything the person set to "never alone" needs their yes signed on the phone.
                val constitution = constitutions.current() ?: emptyConstitution(clock.now())
                ensure(!current.verdict.phoneApproval && !requiresPhoneApproval(action, constitution)) {
                    GovernanceError.ApproveElsewhere("you set this to \"never alone\": approve it on your phone with your fingerprint or face")
                }
                approve(current, decision.edited)
            }
        }
        ensureNotNull(store.get(id)) { GovernanceError.HeldNotFound(id.value) }
    }

    /**
     * A decision signed on the phone (`POST /held/{id}/signed-decision`). The signature must verify
     * against the calling device's registered approval key over the held action's exact content, and
     * works once. Vault actions are not approved here: they are approved by their release.
     */
    suspend fun decideSigned(id: HeldActionId, device: DeviceId, request: SignedDecision): Either<GovernanceError, HeldAction> = either {
        val current = ensureNotNull(store.get(id)) { GovernanceError.HeldNotFound(id.value) }
        ensure(current.status in PENDING_STATUSES) { GovernanceError.HeldNotPending(id.value, current.status.name.lowercase()) }
        ensure(request.decision == SignedDecision.APPROVE || request.decision == SignedDecision.DECLINE) {
            GovernanceError.BadSignature("decision must be 'approve' or 'decline'")
        }
        val key = ensureNotNull(vault.approvalKey(device)) { GovernanceError.BadSignature("this device has no approval key registered") }
        ensure(PhoneApprovals.verify(key, ApprovalPayload.payloadFor(current, request.decision), request.signature)) {
            GovernanceError.BadSignature("the signature does not match this action")
        }
        ensure(vault.markSignatureUsed(PhoneApprovals.signatureHash(request.signature), id.value, device)) { GovernanceError.SignatureReplayed(id.value) }
        if (request.decision == SignedDecision.DECLINE) {
            ensure(finish(current, HeldAction.Status.DECLINED)) { GovernanceError.HeldNotPending(id.value, "decided") }
        } else {
            ensure(current.action.kind !in VAULT_KINDS) { GovernanceError.ApproveElsewhere("this one is approved by releasing the secret, from the vault screen") }
            approve(current, null)
        }
        ensureNotNull(store.get(id)) { GovernanceError.HeldNotFound(id.value) }
    }

    context(_: Raise<GovernanceError>)
    private suspend fun approve(current: HeldAction, edited: ProposedAction?) {
        val id = current.id
        val action = edited ?: current.action
        // Browser actions are done by the backend itself when the agent repeats the call: approving only records the yes.
        if (isLocalAction(action)) {
            ensure(edited == null) { GovernanceError.InvalidEdit("a browser action cannot be edited") }
            val constitution = constitutions.current() ?: emptyConstitution(clock.now())
            hardLineViolation(action, current.verdict, constitution)?.let { raise(GovernanceError.HardLine(it)) }
            ensure(store.transition(id, PENDING_STATUSES, HeldAction.Status.APPROVED)) { GovernanceError.HeldNotPending(id.value, "decided") }
            publish(id)
            return
        }
        val constitution = constitutions.current() ?: emptyConstitution(clock.now())
        hardLineViolation(action, current.verdict, constitution)?.let { raise(GovernanceError.HardLine(it)) }
        val connector = router.select(action.kind)
        if (connector == null) {
            store.transition(id, PENDING_STATUSES, HeldAction.Status.FAILED, "No connector can do \"${action.kind.label()}\" yet.")
            publish(id)
            raise(GovernanceError.NoHandler(action.kind.label()))
        }
        val now = clock.now()
        val changed = database.tx {
            val moved = with(store) {
                transitionTx(id, PENDING_STATUSES, HeldAction.Status.APPROVED, now, edited = edited)
            }
            if (moved) with(outbox) { enqueueTx(connector, action, current.action.idempotencyKey, id, alone = false) }
            moved
        }
        ensure(changed) { GovernanceError.HeldNotPending(id.value, "decided") }
        outbox.wake()
        publish(id)
    }

    private suspend fun finish(current: HeldAction, to: HeldAction.Status): Boolean =
        store.transition(current.id, PENDING_STATUSES, to).also { if (it) publish(current.id) }

    private suspend fun publish(id: HeldActionId) {
        store.get(id)?.let { events.publish(GovernanceEvent.HeldChanged(it)) }
    }
}

/** Read access to "see allowed actions". */
@Inject
@SingleIn(AppScope::class)
class ActivityStore(private val database: Database) {
    suspend fun recent(limit: Int = 50): List<ActivityEntry> = database.tx {
        ActivityTable.selectAll().orderBy(ActivityTable.at, SortOrder.DESC).orderBy(ActivityTable.id, SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map { ActivityEntry(it[ActivityTable.at], it[ActivityTable.summary], kindOf(it[ActivityTable.kind]), it[ActivityTable.ok]) }
    }

    private fun kindOf(wire: String) =
        net.robog.clarity.backend.persistence.parseWire<net.robog.clarity.shared.governance.ActionKind>(wire)
            ?: net.robog.clarity.shared.governance.ActionKind.EXTERNAL_TOOL
}
