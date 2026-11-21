package net.robog.clarity.app.connector

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle

/** What goes into the address book. */
data class NewContact(val name: String, val phones: List<String>, val emails: List<String>, val organization: String?)

/** Pure: the action as an address book entry. Numbers keep the person's spelling. */
fun newContactFrom(action: ProposedAction.AddContact): NewContact {
    fun values(kind: Handle.Kind) = action.handles.filter { it.kind == kind }.map { it.value.trim() }.filter(String::isNotEmpty).distinct()
    return NewContact(action.name.trim(), values(Handle.Kind.PHONE), values(Handle.Kind.EMAIL), action.organization?.trim()?.takeIf(String::isNotEmpty))
}

/** The phone's address book. */
interface AddressBook {
    /** Contacts can be written. */
    fun canWrite(): Boolean

    /** The name of a saved contact that has one of these numbers; null when none does, or when contacts cannot be read. */
    fun savedAs(phones: List<String>): String?

    /** Saves [contact] and returns the id of the new raw contact. */
    fun insert(contact: NewContact): Long
}

/** [ContactsContract] on the device. A new contact belongs to no account, so it stays on this phone. */
class AndroidAddressBook(private val context: Context, private val resolver: ContentResolver = context.contentResolver) : AddressBook {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CONTACTS)

    override fun savedAs(phones: List<String>): String? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        for (phone in phones) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            val name = resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (name != null) return name
        }
        return null
    }

    override fun insert(contact: NewContact): Long {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        fun data(mime: String, build: ContentProviderOperation.Builder.() -> Unit) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
                .apply(build)
                .build()
        }
        data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
        }
        contact.phones.forEach { phone ->
            data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
        }
        contact.emails.forEach { email ->
            data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
            }
        }
        contact.organization?.let { org ->
            data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)
            }
        }
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return results.first().uri?.lastPathSegment?.toLongOrNull() ?: error("the address book gave no id")
    }
}

/** A finished action, and the contact record to tell the server about when one was saved. */
data class ContactResult(val outcome: ActionOutcome, val record: IngestRecord? = null)

/** Executes `ADD_CONTACT` from the action channel. */
class ContactActionHandler(private val book: AddressBook, private val clock: AppClock) {
    suspend fun handle(action: ProposedAction): ContactResult {
        if (action !is ProposedAction.AddContact) {
            return ContactResult(ActionOutcome.Failed("This phone only adds contacts here.", retryable = false))
        }
        if (!book.canWrite()) {
            return ContactResult(ActionOutcome.Failed("Contacts access is off. Allow it in Settings, under Sources.", retryable = false))
        }
        val contact = newContactFrom(action)
        return withContext(Dispatchers.IO) {
            try {
                book.savedAs(contact.phones)?.let { name ->
                    return@withContext ContactResult(ActionOutcome.Done(content = "Already saved as $name. Nothing added."))
                }
                val id = book.insert(contact)
                val sourceId = SourceId("phone-contact:$id")
                // History: the person's own action, so it wakes nothing.
                val record = IngestRecord.Upsert(
                    sourceId, "1", clock.now(),
                    ContactPayload(contact.name, contact.phones.map { Handle.phone(it) } + contact.emails.map { Handle.email(it) }, contact.organization),
                    history = true,
                )
                ContactResult(ActionOutcome.Done(ref = sourceId, content = "Saved ${contact.name} to this phone."), record)
            } catch (e: SecurityException) {
                ContactResult(ActionOutcome.Failed("Contacts access was turned off. Allow it in Settings, under Sources.", retryable = false))
            } catch (e: Exception) {
                ContactResult(ActionOutcome.Failed("The address book would not save it: ${e.message ?: e::class.simpleName}", retryable = true))
            }
        }
    }
}
