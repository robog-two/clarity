package net.robog.clarity.app.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

internal class FakeBook : AddressBook {
    var writable = true
    var existing: String? = null
    var failure: Exception? = null
    val saved = mutableListOf<NewContact>()
    override fun canWrite() = writable
    override fun savedAs(phones: List<String>) = existing
    override fun insert(contact: NewContact): Long {
        failure?.let { throw it }
        saved += contact
        return saved.size.toLong()
    }
}

private fun add(vararg handles: Handle, name: String = "Dana", org: String? = null) =
    ProposedAction.AddContact("k", "Add contact $name", name, handles.toList(), org)

class AddContactTest {
    private val book = FakeBook()
    private val handler = ContactActionHandler(book, FixedClock(T0))

    @Test fun theActionBecomesAnEntryWithNumbersAsWritten() {
        val c = newContactFrom(add(Handle.phone(" 555 0100 "), Handle.phone("555 0100"), Handle.email("d@x.example"), org = " Acme "))
        assertEquals(NewContact("Dana", listOf("555 0100"), listOf("d@x.example"), "Acme"), c)
    }

    @Test fun savesTheContactAndHandsBackARecordForTheServer() = runBlocking {
        val r = handler.handle(add(Handle.phone("555 0100"), Handle.email("d@x.example")))
        val done = assertIs<ActionOutcome.Done>(r.outcome)
        assertEquals("phone-contact:1", done.ref?.value)
        assertEquals(1, book.saved.size)
        assertEquals("phone-contact:1", r.record?.sourceId?.value)
    }

    @Test fun withoutWriteAccessItFailsWithoutRetryAndSavesNothing() = runBlocking {
        book.writable = false
        val r = handler.handle(add(Handle.phone("555 0100")))
        val failed = assertIs<ActionOutcome.Failed>(r.outcome)
        assertFalse(failed.retryable)
        assertTrue(failed.message.contains("Settings"))
        assertTrue(book.saved.isEmpty())
        assertNull(r.record)
    }

    @Test fun aNumberAlreadySavedIsNotAddedAgain() = runBlocking {
        book.existing = "Dana Reyes"
        val r = handler.handle(add(Handle.phone("555 0100")))
        assertTrue((r.outcome as ActionOutcome.Done).content!!.contains("Dana Reyes"))
        assertTrue(book.saved.isEmpty())
        assertNull(r.record)
    }

    @Test fun aRevokedPermissionMidwayIsNotRetriedButOtherErrorsAre() = runBlocking {
        book.failure = SecurityException("denied")
        assertFalse(assertIs<ActionOutcome.Failed>(handler.handle(add(Handle.phone("1"))).outcome).retryable)
        book.failure = IllegalStateException("busy")
        assertTrue(assertIs<ActionOutcome.Failed>(handler.handle(add(Handle.phone("1"))).outcome).retryable)
    }

    @Test fun otherActionsAreRefused() = runBlocking {
        assertIs<ActionOutcome.Failed>(handler.handle(ProposedAction.SetDoNotDisturb("k", "s", T0)).outcome)
        Unit
    }
}
