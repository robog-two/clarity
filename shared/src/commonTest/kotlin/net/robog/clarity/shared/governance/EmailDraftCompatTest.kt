package net.robog.clarity.shared.governance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.noun.Handle

class EmailDraftCompatTest {
    /** A held send stored before emails named their sender and thread. */
    private val oldHeld = """
        {"id":"h1","createdAt":"2026-10-26T14:04:00Z",
         "action":{"type":"send_email","idempotencyKey":"k1","summary":"Send \"Missing class today\" to Prof. Lee",
                   "email":{"to":[{"kind":"email","value":"lee@uni.edu","displayName":"Prof. Lee"}],"subject":"Missing class today","body":"Hi"}},
         "verdict":{"decision":"ask","rationale":"It goes out as you."},
         "agentView":"You asked me to tell them.","urgency":"normal","status":"held"}
    """.trimIndent()

    @Test
    fun oldHeldEmailDecodesWithoutSenderOrThread() {
        val held = ClarityJson.decodeFromString(HeldAction.serializer(), oldHeld)
        val email = (held.action as ProposedAction.SendEmail).email
        assertNull(email.from)
        assertNull(email.fromLabel)
        assertNull(email.inReplyTo)
        assertNull(email.replySubject)
        assertEquals("Missing class today", email.subject)
    }

    @Test
    fun newEmailRoundTripsSenderAndThread() {
        val email = EmailDraft(
            from = Handle.email("samuel.elliot.knight@gmail.com"), fromLabel = "Gmail",
            to = listOf(Handle.email("dana@x.com")), subject = "Re: Lunch", body = "Yes",
            inReplyTo = EntityId("e1"), replySubject = "Lunch",
        )
        val json = ClarityJson.encodeToString(EmailDraft.serializer(), email)
        assertEquals(email, ClarityJson.decodeFromString(EmailDraft.serializer(), json))
    }

    @Test
    fun manifestWithoutAccountsStillDecodes() {
        val manifest = ClarityJson.decodeFromString(ConnectorManifest.serializer(), """{"kind":"mail","displayName":"Mail","version":"1","produces":[]}""")
        assertEquals(emptyList(), manifest.accounts)
        val with = manifest.copy(accounts = listOf(ConnectorAccount("Gmail", "samuel.elliot.knight@gmail.com")))
        assertEquals(with, ClarityJson.decodeFromString(ConnectorManifest.serializer(), ClarityJson.encodeToString(ConnectorManifest.serializer(), with)))
    }
}
