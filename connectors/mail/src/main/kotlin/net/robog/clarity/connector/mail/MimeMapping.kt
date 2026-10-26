package net.robog.clarity.connector.mail

import jakarta.mail.Address
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import java.security.MessageDigest
import kotlin.time.Instant
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import org.jsoup.Jsoup

/** Longest body kept per message; mail bodies can be megabytes of quoted history. */
const val MAX_BODY_CHARS = 200_000

private val ANGLE_ID = Regex("<[^<>\\s]+>")

/**
 * Maps a parsed MIME message to the normalized payload, or null when it cannot be one (no sender).
 * Pure apart from reading the message. [account] is the user's own address on this mailbox.
 */
fun MimeMessage.toPayload(account: String, fallbackTime: Instant): MessagePayload? {
    val from = (from?.firstOrNull() ?: sender)?.toHandle() ?: return null
    val recipients = listOf(Message.RecipientType.TO, Message.RecipientType.CC)
        .flatMap { type -> getRecipients(type)?.mapNotNull { it.toHandle() }.orEmpty() }
    val sent = (sentDate ?: receivedDate)?.toInstant()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) } ?: fallbackTime
    return MessagePayload(
        channel = MessagePayload.Channel.EMAIL,
        account = Handle.email(account),
        sender = from,
        recipients = recipients,
        subject = subject?.let { decodeHeader(it) }?.takeIf(String::isNotBlank),
        body = bodyText().take(MAX_BODY_CHARS),
        sentAt = sent,
        threadRef = threadRef(),
    )
}

fun Address.toHandle(): Handle? {
    val ia = this as? InternetAddress ?: return null
    val address = ia.address?.trim()?.takeIf { it.contains('@') } ?: return null
    return Handle.email(address, ia.personal?.let { decodeHeader(it) }?.takeIf(String::isNotBlank))
}

private fun decodeHeader(raw: String): String = try { MimeUtility.decodeText(raw) } catch (_: Exception) { raw }

/** The conversation id: the root of `References`, else `In-Reply-To`, else the message's own id. */
fun MimeMessage.threadRef(): String? {
    val references = getHeader("References", " ")?.let { ANGLE_ID.findAll(it).map { m -> m.value }.firstOrNull() }
    val inReplyTo = getHeader("In-Reply-To", " ")?.let { ANGLE_ID.find(it)?.value }
    return references ?: inReplyTo ?: messageID?.trim()?.takeIf(String::isNotEmpty)
}

/** Plain text of the message: the first text/plain part, else the first text/html part as text. Attachments are skipped. */
fun Part.bodyText(): String {
    val plain = StringBuilder()
    val html = StringBuilder()
    collectText(this, plain, html)
    val text = plain.toString().ifBlank { if (html.isBlank()) "" else htmlToText(html.toString()) }
    return text.replace("\r\n", "\n").trim()
}

private fun collectText(part: Part, plain: StringBuilder, html: StringBuilder) {
    if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return
    when {
        part.isMimeType("text/plain") -> if (plain.isEmpty()) plain.append(part.content.asText())
        part.isMimeType("text/html") -> if (html.isEmpty()) html.append(part.content.asText())
        part.isMimeType("multipart/*") -> {
            val multipart = part.content as? jakarta.mail.Multipart ?: return
            for (i in 0 until multipart.count) collectText(multipart.getBodyPart(i), plain, html)
        }
        part.isMimeType("message/rfc822") -> Unit // forwarded messages are attachments for our purposes
    }
}

private fun Any?.asText(): String = when (this) {
    is String -> this
    is java.io.InputStream -> readBytes().toString(Charsets.UTF_8)
    else -> ""
}

fun htmlToText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.select("br").append("\\n")
    doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6").prepend("\\n")
    return doc.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
}

/** `<sha256(key)[0..32]@domain>`: the same idempotency key always yields the same Message-ID, so a resend is detectable. */
fun messageIdFor(idempotencyKey: String, fromAddress: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(idempotencyKey.toByteArray(Charsets.UTF_8))
    val local = digest.joinToString("") { "%02x".format(it) }.take(32)
    val domain = fromAddress.substringAfter('@', "clarity.local").trim().ifEmpty { "clarity.local" }
    return "<$local@$domain>"
}

/**
 * Source id of a mail record: `account/folder/uidvalidity/uid` (the folder may itself contain
 * slashes). The old form `folder/uidvalidity/uid` belongs to account [DEFAULT_ACCOUNT].
 */
data class MailRef(val folder: String, val uidValidity: Long, val uid: Long, val accountId: String = DEFAULT_ACCOUNT) {
    val sourceId: String get() = "$accountId/$folder/$uidValidity/$uid"

    companion object {
        /**
         * Parses [sourceId]. With four or more parts the first is the account; a legacy folder
         * that contains a slash looks the same, so pass the configured [accountIds] to tell them
         * apart (a first part that is not one of them makes it a legacy id).
         */
        fun parse(sourceId: String, accountIds: Collection<String>? = null): MailRef? {
            val parts = sourceId.split('/')
            if (parts.size < 3) return null
            val uid = parts.last().toLongOrNull() ?: return null
            val validity = parts[parts.size - 2].toLongOrNull() ?: return null
            val prefixed = parts.size >= 4 && (accountIds == null || parts.first() in accountIds)
            return if (prefixed) MailRef(parts.subList(1, parts.size - 2).joinToString("/"), validity, uid, parts.first())
            else MailRef(parts.dropLast(2).joinToString("/"), validity, uid)
        }
    }
}

/** A compact set of UIDs stored as ranges (`1-50,52,60-99`), so state stays small for big mailboxes. */
class UidSet private constructor(private val uids: java.util.TreeSet<Long>) {
    constructor(values: Collection<Long> = emptyList()) : this(java.util.TreeSet(values))

    val size: Int get() = uids.size
    operator fun contains(uid: Long): Boolean = uid in uids
    fun toSet(): Set<Long> = uids.toSet()
    fun max(): Long? = uids.lastOrNull()

    fun encode(): String {
        val out = StringBuilder()
        var start: Long? = null
        var prev: Long? = null
        fun flush() {
            val s = start ?: return
            if (out.isNotEmpty()) out.append(',')
            out.append(if (s == prev) "$s" else "$s-$prev")
        }
        for (u in uids) {
            if (prev != null && u == prev!! + 1) { prev = u; continue }
            flush(); start = u; prev = u
        }
        flush()
        return out.toString()
    }

    companion object {
        fun decode(encoded: String): UidSet {
            val set = java.util.TreeSet<Long>()
            encoded.split(',').filter(String::isNotBlank).forEach { piece ->
                val bounds = piece.split('-')
                val lo = bounds[0].trim().toLongOrNull() ?: return@forEach
                val hi = bounds.getOrNull(1)?.trim()?.toLongOrNull() ?: lo
                for (u in lo..hi) set.add(u)
            }
            return UidSet(set)
        }
    }
}
