package net.robog.clarity.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat

/*
 * Names of the people the person texts most, so the speech recognizers can spell them right. Everything
 * is read on this phone (the texts and the saved contacts) and nothing leaves it. The ordering and merging
 * are pure functions; the Android providers sit behind two small interfaces.
 */

/** How many contacts to hint, and how many of the newest messages to look through at most. */
const val MAX_CONTACTS = 20
const val MAX_SCANNED_TEXTS = 500

/** The sender or recipient of each text, newest first. An address can repeat. */
fun interface RecentTexts {
    fun addresses(limit: Int): List<String>
}

/** The display name of the saved contact with this phone number, or null when there is none. */
fun interface ContactBook {
    fun nameFor(address: String): String?
}

/**
 * The distinct contacts behind the addresses, in the order they were first seen (newest text first),
 * at most [max]. Addresses that are not saved contacts are skipped and do not count towards [max]:
 * one-time codes, short codes, marketing and unknown numbers. Pure.
 */
fun recentContactNames(addresses: List<String>, lookup: (String) -> String?, max: Int = MAX_CONTACTS): List<String> {
    val seenNumbers = HashSet<String>()
    val names = LinkedHashMap<String, String>()
    for (address in addresses) {
        if (names.size >= max) break
        if (!looksLikePhoneNumber(address)) continue
        if (!seenNumbers.add(address.filter { it.isDigit() }.takeLast(10))) continue
        val name = lookup(address)?.trim().orEmpty()
        if (name.isNotEmpty()) names.putIfAbsent(name.lowercase(), name)
    }
    return names.values.toList()
}

/** A real phone number: at least seven digits and no letters (short codes and sender names are out). */
fun looksLikePhoneNumber(address: String): Boolean =
    address.count { it.isDigit() } >= 7 && address.none { it.isLetter() }

/**
 * Hint words for the recognizers: for each contact the full name and then its words ("Sam Example"
 * gives "Sam Example", "Sam", "Example"). Contacts only: words from task titles used to be added, and a
 * silent phrase came out as those words repeated. Letters, apostrophes and hyphens only (what the second
 * pass can encode), no repeats, at most [max]. Pure.
 */
fun contactHotwords(contactNames: List<String>, max: Int = MAX_HOTWORDS): List<String> {
    val out = LinkedHashMap<String, String>()
    fun add(phrase: String) {
        val p = phrase.trim()
        if (p.length >= 2 && p.all { it in 'A'..'Z' || it in 'a'..'z' || it == '\'' || it == '-' || it == ' ' }) {
            out.putIfAbsent(p.lowercase(), p)
        }
    }
    for (name in contactNames) {
        val words = name.split(Regex("[^\\p{L}'-]+")).map { it.trim('\'', '-') }.filter { it.isNotEmpty() }
        if (words.size > 1) add(words.joinToString(" "))
        words.forEach(::add)
    }
    return out.values.take(max)
}

/** Keeps a loaded value for [ttlMs], so each message does not query the providers again. */
class TimedCache<T : Any>(private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var value: T? = null
    private var at = 0L

    @Synchronized
    fun get(load: () -> T): T {
        val t = now()
        val v = value
        if (v != null && t - at < ttlMs) return v
        return load().also { value = it; at = t }
    }

    @Synchronized
    fun peek(): T? = value?.takeIf { now() - at < ttlMs }

    @Synchronized
    fun clear() { value = null }
}

/** Display names of the saved contacts the person texted most recently. Empty without both permissions. */
class RecentContactNames(
    private val granted: () -> Boolean,
    private val texts: RecentTexts,
    private val contacts: ContactBook,
    private val max: Int = MAX_CONTACTS,
    private val scan: Int = MAX_SCANNED_TEXTS,
) {
    fun names(): List<String> {
        if (!granted()) return emptyList()
        return runCatching { recentContactNames(texts.addresses(scan).take(scan), contacts::nameFor, max) }
            .getOrDefault(emptyList())
    }

    companion object {
        fun permitted(context: Context): Boolean =
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun of(context: Context) = RecentContactNames(
            granted = { permitted(context) },
            texts = AndroidRecentTexts(context),
            contacts = AndroidContactBook(context),
        )
    }
}

private class AndroidRecentTexts(private val context: Context) : RecentTexts {
    override fun addresses(limit: Int): List<String> {
        val out = ArrayList<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (out.size < limit && c.moveToNext()) c.getString(0)?.let(out::add)
        }
        return out
    }
}

private class AndroidContactBook(private val context: Context) : ContactBook {
    override fun nameFor(address: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }
}
