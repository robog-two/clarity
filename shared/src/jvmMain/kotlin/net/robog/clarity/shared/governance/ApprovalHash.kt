package net.robog.clarity.shared.governance

import java.security.MessageDigest

/** Both the backend and the Android app are JVM: the hash lives here so they cannot disagree. */
fun ApprovalPayload.actionHash(action: ProposedAction): String =
    MessageDigest.getInstance("SHA-256").digest(canonicalJson(action).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun ApprovalPayload.payloadFor(held: HeldAction, decision: String): String =
    payload(held.id, actionHash(held.action), decision)
