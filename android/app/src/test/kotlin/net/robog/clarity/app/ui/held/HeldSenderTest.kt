package net.robog.clarity.app.ui.held

import kotlin.test.Test
import kotlin.test.assertEquals
import net.robog.clarity.app.testutil.emailDraft
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

class HeldSenderTest {
    @Test
    fun theSenderShowsItsAccountNameAndAddress() {
        val email = emailDraft().copy(from = Handle.email("samuel.elliot.knight@gmail.com"), fromLabel = "Gmail")
        assertEquals("Gmail · samuel.elliot.knight@gmail.com", senderLine(email))
        // a label that is just the address is not repeated
        assertEquals("me@x.com", senderLine(email.copy(from = Handle.email("me@x.com"), fromLabel = "me@x.com")))
        assertEquals("me@x.com", senderLine(email.copy(from = Handle.email("me@x.com"), fromLabel = null)))
    }

    @Test
    fun aReplyNamesItsThreadAndANewEmailSaysSo() {
        val email = emailDraft()
        assertEquals("New email", threadLine(email))
        assertEquals("Reply in “Revised Terms”", threadLine(email.copy(inReplyTo = EntityId("m1"), replySubject = "Revised Terms")))
        assertEquals("Reply in an existing thread", threadLine(email.copy(inReplyTo = EntityId("m1"))))
    }

    @Test
    fun anEmailHeldBeforeTheSenderWasChosenStillShows() {
        val stored = """{"type":"send_email","idempotencyKey":"k1","summary":"Send \"Missing class today\" to Prof. Lee",
            "email":{"to":[{"kind":"email","value":"lee@uni.edu"}],"subject":"Missing class today","body":"Hi"}}"""
        val email = emailOf(ClarityJson.decodeFromString(ProposedAction.serializer(), stored))!!
        assertEquals("Default account", senderLine(email))
        assertEquals("New email", threadLine(email))
    }
}
