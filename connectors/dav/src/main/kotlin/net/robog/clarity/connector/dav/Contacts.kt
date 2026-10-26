package net.robog.clarity.connector.dav

import ezvcard.Ezvcard
import ezvcard.VCard
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle

/** Contacts in [vcf] (a .vcf may hold several cards); cards with neither a name nor a handle are dropped. */
fun contactsFrom(vcf: String): List<ContactPayload> =
    Ezvcard.parse(vcf).all().mapNotNull { it.toContact() }

fun VCard.toContact(): ContactPayload? {
    val emails = emails.mapNotNull { e -> e.value?.trim()?.takeIf { it.contains('@') }?.let { Handle.email(it) } }
    val phones = telephoneNumbers.mapNotNull { t -> (t.text ?: t.uri?.number)?.trim()?.takeIf { it.any(Char::isDigit) }?.let { Handle.phone(it) } }
    val name = formattedName?.value?.trim()?.takeIf(String::isNotEmpty)
        ?: structuredName?.let { n -> listOfNotNull(n.given, n.family).joinToString(" ").trim() }?.takeIf(String::isNotEmpty)
        ?: organization?.values?.firstOrNull()?.trim()
        ?: ""
    val handles = emails + phones
    if (name.isEmpty() && handles.isEmpty()) return null
    return ContactPayload(
        displayName = name.ifEmpty { handles.first().value },
        handles = handles,
        organization = organization?.values?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty),
        notes = notes.firstOrNull()?.value?.trim()?.takeIf(String::isNotEmpty),
    )
}
