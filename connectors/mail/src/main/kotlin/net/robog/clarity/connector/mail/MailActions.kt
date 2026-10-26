package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.HeaderTerm
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.connector.sdk.ActionHandlers
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.actionHandlers
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

private val log = KotlinLogging.logger {}

/** Threading headers copied from the message being answered. */
data class ReplyHeaders(val inReplyTo: String, val references: String)

/** `SAVE_DRAFT` and `SEND_EMAIL`. Credentials come from the runtime's newest config on every action. */
class MailActions(private val access: suspend () -> MailAccess) {
    constructor(runtime: ConnectorRuntime) : this({ MailAccess(parseMailConfig(runtime.awaitConfig(), runtime)) })

    fun handlers(): ActionHandlers = actionHandlers {
        onTargeted<ProposedAction.SaveDraft> { action, target -> saveDraft(action, target) }
        onTargeted<ProposedAction.SendEmail> { action, target -> sendEmail(action, target) }
    }

    /** Maps expected failures to outcomes; unexpected exceptions propagate and are retried by the SDK. */
    private suspend fun guarded(block: suspend () -> ActionOutcome): ActionOutcome = try {
        block()
    } catch (e: ConfigIncomplete) {
        ActionOutcome.Failed("The mail connector is not configured: ${e.message}", retryable = false)
    } catch (e: AuthenticationFailedException) {
        ActionOutcome.Failed("The mail server refused the login: ${e.message}. Ask the person to check the password in the mail settings.", retryable = false)
    } catch (e: InvalidDraft) {
        ActionOutcome.Failed(e.message ?: "invalid draft", retryable = false)
    } catch (e: SendFailedException) {
        val invalid = e.invalidAddresses?.joinToString { it.toString() }
        if (invalid.isNullOrEmpty()) throw e
        ActionOutcome.Failed("The mail server rejected the recipient address(es): $invalid", retryable = false)
    }

    suspend fun saveDraft(action: ProposedAction.SaveDraft, target: SourceId?): ActionOutcome = guarded { doSaveDraft(action, target) }

    suspend fun sendEmail(action: ProposedAction.SendEmail, target: SourceId?): ActionOutcome = guarded { doSendEmail(action, target) }

    private suspend fun doSaveDraft(action: ProposedAction.SaveDraft, target: SourceId?): ActionOutcome = withContext(Dispatchers.IO) {
        val acc = access.invoke()
        val messageId = messageIdFor(action.idempotencyKey, acc.config.fromAddress)
        acc.imap().use { store ->
            val reply = replyHeaders(store, target)
            val message = buildMessage(acc.smtpSession(), acc.config, action.email, messageId, reply)
            val drafts = store.specialFolder("\\Drafts", "Drafts", "Draft", "INBOX.Drafts") ?: store.folder("Drafts").also { it.create(jakarta.mail.Folder.HOLDS_MESSAGES) }
            // A re-run of the same draft replaces the earlier copy instead of adding another.
            drafts.openedUse(READ_WRITE) { f ->
                val old = f.search(HeaderTerm("Message-ID", messageId))
                if (old.isNotEmpty()) {
                    f.setFlags(old, Flags(Flags.Flag.DELETED), true)
                    f.expunge()
                }
            }
            message.setFlag(Flags.Flag.DRAFT, true)
            message.setFlag(Flags.Flag.SEEN, true)
            val appended = drafts.appendUIDMessages(arrayOf(message))
            val ref = appended?.firstOrNull()?.let { MailRef(drafts.fullName, it.uidvalidity, it.uid).sourceId } ?: messageId
            ActionOutcome.Done(SourceId(ref))
        }
    }

    private suspend fun doSendEmail(action: ProposedAction.SendEmail, target: SourceId?): ActionOutcome = withContext(Dispatchers.IO) {
        val acc = access.invoke()
        val messageId = messageIdFor(action.idempotencyKey, acc.config.fromAddress)
        acc.imap().use { store ->
            val sent = store.specialFolder("\\Sent", "Sent", "Sent Items", "Sent Messages", "INBOX.Sent")
            if (sent != null && sent.contains(messageId)) {
                log.info { "message $messageId is already in ${sent.fullName}; not sending again" }
                return@use ActionOutcome.Done(SourceId(messageId))
            }
            val reply = replyHeaders(store, target)
            val session = acc.smtpSession()
            val message = buildMessage(session, acc.config, action.email, messageId, reply)
            val recipients = (action.email.to + action.email.cc).map { it.toAddress() }.toTypedArray()
            if (recipients.isEmpty()) throw InvalidDraft("The email has no recipients.")
            session.getTransport().use { transport ->
                transport.connect(acc.config.smtpHost, acc.config.smtpPort, acc.config.username, acc.config.password)
                transport.sendMessage(message, recipients)
            }
            // Many servers file sent mail themselves; copy it only when they did not.
            val target2 = sent ?: store.folder("Sent").also { if (!it.exists()) it.create(jakarta.mail.Folder.HOLDS_MESSAGES) }
            if (!target2.contains(messageId)) {
                message.setFlag(Flags.Flag.SEEN, true)
                target2.appendMessages(arrayOf(message))
            }
            ActionOutcome.Done(SourceId(messageId))
        }
    }

    /** True when [folder] holds a message with this Message-ID. */
    private fun IMAPFolder.contains(messageId: String): Boolean =
        openedUse(READ_ONLY) { f -> f.search(HeaderTerm("Message-ID", messageId)).isNotEmpty() }

    /** `In-Reply-To`/`References` for a reply to the message at [target] (a `folder/uidvalidity/uid` source id). */
    private fun replyHeaders(store: IMAPStore, target: SourceId?): ReplyHeaders? {
        val ref = target?.value?.let(MailRef::parse) ?: return null
        val folder = store.folder(ref.folder)
        if (!folder.exists()) return null
        return folder.openedUse(READ_ONLY) { f ->
            if (f.uidValidity != ref.uidValidity) return@openedUse null
            val original = f.getMessageByUID(ref.uid) as? MimeMessage ?: return@openedUse null
            val id = original.messageID ?: return@openedUse null
            val earlier = original.getHeader("References", " ")?.trim().orEmpty()
            ReplyHeaders(id, if (earlier.isEmpty()) id else "$earlier $id")
        }
    }
}

class InvalidDraft(message: String) : RuntimeException(message)

private fun Handle.toAddress(): InternetAddress = try {
    InternetAddress(value.trim(), true).also { it.validate(); if (!displayName.isNullOrBlank()) it.personal = displayName!!.replace(Regex("[\\r\\n]+"), " ") }
} catch (e: AddressException) {
    throw InvalidDraft("'$value' is not a valid email address.")
}

private class IdentifiedMessage(session: Session, private val id: String) : MimeMessage(session) {
    override fun updateMessageID() { setHeader("Message-ID", id) }
}

/** Builds the plain-text message; the Message-ID is fixed so the same action always produces the same id. */
fun buildMessage(session: Session, config: MailConfig, draft: EmailDraft, messageId: String, reply: ReplyHeaders?): MimeMessage {
    val message = IdentifiedMessage(session, messageId)
    message.setFrom(InternetAddress(config.fromAddress, config.fromName?.replace(Regex("[\\r\\n]+"), " "), "UTF-8"))
    if (draft.to.isNotEmpty()) message.setRecipients(Message.RecipientType.TO, draft.to.map { it.toAddress() }.toTypedArray())
    if (draft.cc.isNotEmpty()) message.setRecipients(Message.RecipientType.CC, draft.cc.map { it.toAddress() }.toTypedArray())
    message.setSubject(draft.subject.replace(Regex("[\\r\\n]+"), " ").trim(), "UTF-8")
    message.sentDate = Date()
    if (reply != null) {
        message.setHeader("In-Reply-To", reply.inReplyTo)
        message.setHeader("References", reply.references)
    }
    message.setText(draft.body, "UTF-8", "plain")
    message.saveChanges()
    return message
}
