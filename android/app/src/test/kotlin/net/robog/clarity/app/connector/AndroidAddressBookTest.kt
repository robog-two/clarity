package net.robog.clarity.app.connector

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A stand-in for the system contacts provider: it keeps raw contacts and data rows in memory and
 * answers phone lookups. Everything on the app's side of the boundary is real: the operations,
 * the back references, `applyBatch` through the resolver, and the lookup query.
 */
class FakeContactsProvider : ContentProvider() {
    data class Row(val table: String, val id: Long, val values: ContentValues)

    val rows = mutableListOf<Row>()
    private var next = 1L

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val table = if (uri.path.orEmpty().contains("raw_contacts")) "raw" else "data"
        val id = next++
        rows += Row(table, id, ContentValues(values))
        return ContentUris.withAppendedId(uri, id)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val number = Uri.decode(uri.lastPathSegment.orEmpty()).filter(Char::isDigit)
        val data = rows.filter { it.table == "data" }
        val hit = data.firstOrNull {
            it.values.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE &&
                it.values.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER).orEmpty().filter(Char::isDigit) == number
        }
        val cursor = MatrixCursor(arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME))
        if (hit != null) {
            val raw = hit.values.getAsLong(ContactsContract.Data.RAW_CONTACT_ID)
            val name = data.first {
                it.values.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) == raw &&
                    it.values.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            }.values.getAsString(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            cursor.addRow(arrayOf(name))
        }
        return cursor
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun getType(uri: Uri): String? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAddressBookTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeContactsProvider
    private lateinit var book: AndroidAddressBook

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        book = AndroidAddressBook(context)
    }

    private fun data(mime: String) = provider.rows.filter { it.table == "data" && it.values.getAsString(ContactsContract.Data.MIMETYPE) == mime }

    @Test fun insertsOneRawContactWithItsNameNumbersEmailsAndOrganization() {
        val id = book.insert(NewContact("Dana Reyes", listOf("555 0100", "+1 555 0101"), listOf("dana@x.example"), "Acme"))
        val raw = provider.rows.single { it.table == "raw" }
        assertEquals(raw.id, id)
        assertNull(raw.values.getAsString(ContactsContract.RawContacts.ACCOUNT_NAME))
        val name = data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Dana Reyes", name.values.getAsString(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME))
        assertEquals(id, name.values.getAsLong(ContactsContract.Data.RAW_CONTACT_ID))
        val phones = data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        assertEquals(listOf("555 0100", "+1 555 0101"), phones.map { it.values.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER) })
        assertTrue(phones.all { it.values.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) == id })
        assertEquals("dana@x.example", data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE).single().values.getAsString(ContactsContract.CommonDataKinds.Email.ADDRESS))
        assertEquals("Acme", data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE).single().values.getAsString(ContactsContract.CommonDataKinds.Organization.COMPANY))
    }

    @Test fun findsASavedContactByANumberWrittenAnotherWay() {
        book.insert(NewContact("Dana", listOf("5550100"), emptyList(), null))
        assertEquals("Dana", book.savedAs(listOf("999", "555-0100")))
        assertNull(book.savedAs(listOf("555 0199")))
    }

    @Test fun writePermissionIsReadFromTheSystem() {
        assertTrue(book.canWrite())
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_CONTACTS)
        assertEquals(false, book.canWrite())
    }

    @Test fun theHandlerSavesOnceAndRecognisesTheContactNextTime() = runBlocking {
        val handler = ContactActionHandler(book, FixedClock(T0))
        val action = ProposedAction.AddContact("k", "Add contact Dana", "Dana", listOf(Handle.phone("555 0100")))
        val first = handler.handle(action)
        assertIs<ActionOutcome.Done>(first.outcome)
        assertTrue(first.record != null)
        val again = handler.handle(action)
        assertTrue((again.outcome as ActionOutcome.Done).content!!.startsWith("Already saved as Dana"))
        assertNull(again.record)
        assertEquals(1, provider.rows.count { it.table == "raw" })
    }

    @Test fun withoutPermissionTheHandlerWritesNothing() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_CONTACTS)
        val r = ContactActionHandler(book, FixedClock(T0)).handle(ProposedAction.AddContact("k", "s", "Dana", listOf(Handle.phone("555 0100"))))
        assertIs<ActionOutcome.Failed>(r.outcome)
        assertTrue(provider.rows.isEmpty())
    }
}
