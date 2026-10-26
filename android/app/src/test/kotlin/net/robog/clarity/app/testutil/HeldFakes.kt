package net.robog.clarity.app.testutil

import net.robog.clarity.app.data.AmendmentsRepository
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.HeldRepository
import net.robog.clarity.app.data.TodayRepository
import net.robog.clarity.app.ui.held.ApprovalGate
import net.robog.clarity.app.ui.held.ApprovalInput
import net.robog.clarity.app.ui.held.ApprovalResult
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Today

fun emailDraft(body: String = "Hi Dana,\n\nThe terms work for me.\n\nSam") = EmailDraft(
    to = listOf(Handle.email("dana@example.com", "Dana Lee")),
    subject = "Re: Revised Terms",
    body = body,
)

fun sendEmail(body: String = "Hi Dana,\n\nThe terms work for me.\n\nSam") =
    ProposedAction.SendEmail("k1", "reply to dana@example.com", emailDraft(body))

fun heldAction(
    id: String = "h1",
    action: ProposedAction = sendEmail(),
    status: HeldAction.Status = HeldAction.Status.HELD,
    createdAt: kotlin.time.Instant = T0,
    lines: List<LineId> = listOf(LineId("l3")),
) = HeldAction(
    id = HeldActionId(id),
    createdAt = createdAt,
    action = action,
    verdict = GuardVerdict(Decision.ASK, lines, "I’m asking because it touches money (line 3) and goes out as you."),
    agentView = "The only change is the payment schedule. I’d say yes.",
    context = listOf(ContextItem("mail · yesterday 5:40", "Attached the updated draft. Can you sign by end of week?")),
    urgency = Urgency.NORMAL,
    status = status,
)

class FakeToday(var result: ApiResult<Today> = ApiResult.Success(Today(kotlinx.datetime.LocalDate(2026, 10, 26)))) : TodayRepository {
    var calls = 0
    override suspend fun today(): ApiResult<Today> { calls++; return result }
}

class FakeHeldRepo(
    var items: Map<String, HeldAction> = emptyMap(),
    var activity: ApiResult<List<ActivityEntry>> = ApiResult.Success(emptyList()),
) : HeldRepository {
    override suspend fun get(id: HeldActionId): ApiResult<HeldAction> =
        items[id.value]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NotFound(null))
    override suspend fun activity() = activity
}

class FakeAmendments(var proposed: Amendment? = null, var version: Int = 2) : AmendmentsRepository {
    val saved = mutableListOf<AmendmentId>()
    val discarded = mutableListOf<AmendmentId>()
    val requests = mutableListOf<net.robog.clarity.shared.governance.ProposeAmendment>()
    override suspend fun propose(request: net.robog.clarity.shared.governance.ProposeAmendment): ApiResult<Amendment> {
        requests += request
        return ApiResult.Success(Amendment(AmendmentId("p${requests.size}"), request.lineId, null, request.proposed, request.reason, request.fromHeld, Amendment.Status.PROPOSED, request.autonomy))
    }
    var saveFailure: ApiError? = null
    override suspend fun proposed(id: AmendmentId): ApiResult<Amendment?> = ApiResult.Success(proposed?.takeIf { it.id == id })
    override suspend fun save(id: AmendmentId): ApiResult<Constitution> {
        saveFailure?.let { return ApiResult.Failure(it) }
        saved += id
        return ApiResult.Success(Constitution(version = version + 1, lines = emptyList(), updatedAt = T0))
    }
    override suspend fun discard(id: AmendmentId): ApiResult<Amendment> {
        discarded += id
        return ApiResult.Success(proposed!!.copy(status = Amendment.Status.DISCARDED))
    }
}

fun amendment(id: String = "a1", old: String? = "No AI-style formatting in anything sent as me.") = Amendment(
    id = AmendmentId(id),
    lineId = LineId("l1"),
    old = old,
    proposed = Line(LineId("l1"), Section.VALUES, "No AI-style formatting in anything sent as me, except short replies that only confirm a time.", "short replies are fine"),
    reason = "short scheduling replies",
    status = Amendment.Status.PROPOSED,
)

class RecordingGate(var allow: Boolean = true, var result: ApprovalResult? = null) : ApprovalGate {
    val asked = mutableListOf<HeldActionId>()
    val inputs = mutableListOf<ApprovalInput>()
    override suspend fun authorize(held: HeldAction, input: ApprovalInput): ApprovalResult {
        asked += held.id
        inputs += input
        return result ?: if (allow) ApprovalResult.Proceed else ApprovalResult.Denied("Not approved.")
    }
}
