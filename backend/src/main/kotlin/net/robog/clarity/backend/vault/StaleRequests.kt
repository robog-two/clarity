package net.robog.clarity.backend.vault

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import net.robog.clarity.backend.governance.ClosedStatus
import net.robog.clarity.backend.governance.Closing
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus

/**
 * How long a credential form stays open. The agent asks for a login in the middle of a task; a
 * week later that task has moved on (or the agent asks again when it needs it), and a week still
 * covers a weekend away from the phone.
 */
val CREDENTIAL_REQUEST_MAX_AGE: Duration = 7.days

/** Pure: what makes two requests (or a request and a saved credential) the same: the site's registrable domain and the kind. */
data class CredentialSlot(val domain: String, val kind: CredentialKind)

fun slotOf(site: String, kind: CredentialKind): CredentialSlot =
    CredentialSlot(Sites.registrableDomain(site) ?: Sites.host(site) ?: site.trim().lowercase(), kind)

fun CredentialRequest.slot(): CredentialSlot = slotOf(site, kind)

fun CredentialMeta.slot(): CredentialSlot = slotOf(site, kind)

/**
 * Pure: the open requests that nobody needs any more, rules tried in order:
 * 1. a credential for the same site and kind was saved after the request was made → superseded;
 * 2. a later request for the same site and kind was fulfilled → superseded;
 * 3. a newer open request for the same site and kind exists → the older one is superseded;
 * 4. older than [maxAge] → expired.
 * Fulfilled and cancelled rows are only read, never changed.
 */
fun staleRequests(
    requests: List<CredentialRequest>,
    credentials: List<CredentialMeta>,
    now: Instant,
    maxAge: Duration = CREDENTIAL_REQUEST_MAX_AGE,
): List<Closing<CredentialRequestId>> {
    val open = requests.filter { it.status == CredentialRequestStatus.OPEN }
    val fulfilled = requests.filter { it.status == CredentialRequestStatus.FULFILLED }
    val newestOpen = open.groupBy { it.slot() }.mapValues { (_, same) -> same.maxBy { it.createdAt }.id }
    return open.mapNotNull { r ->
        val slot = r.slot()
        val savedLater = credentials.firstOrNull { it.slot() == slot && it.createdAt > r.createdAt }
        val fulfilledLater = fulfilled.firstOrNull { it.slot() == slot && it.createdAt > r.createdAt }
        when {
            savedLater != null -> Closing(r.id, ClosedStatus.SUPERSEDED, "already saved as ${savedLater.name}")
            fulfilledLater != null -> Closing(r.id, ClosedStatus.SUPERSEDED, "already saved as ${fulfilledLater.name}")
            newestOpen.getValue(slot) != r.id -> Closing(r.id, ClosedStatus.SUPERSEDED, "a newer request for the same login replaced it")
            now - r.createdAt > maxAge -> Closing(r.id, ClosedStatus.EXPIRED, "nobody filled it in within ${maxAge.inWholeDays} days")
            else -> null
        }
    }
}
