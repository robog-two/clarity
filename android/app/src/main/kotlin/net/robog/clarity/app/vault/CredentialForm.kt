package net.robog.clarity.app.vault

import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.Validators

/** Fields whose content is hidden while typing (with show/hide). */
fun FieldType.isSecret(): Boolean = when (this) {
    FieldType.PASSWORD, FieldType.CARD_NUMBER, FieldType.CARD_CVC, FieldType.OTP -> true
    else -> false
}

/**
 * The credential form as plain data: the agent's request, what has been typed and which fields the
 * person has touched. Pure, so validation and normalization are unit-testable. It lives in memory
 * only; it is never written to disk or saved with the activity's state.
 */
data class CredentialForm(
    val request: CredentialRequest,
    val nowYear: Int,
    val nowMonth: Int,
    val values: Map<String, String> = emptyMap(),
    val touched: Set<String> = emptySet(),
) {
    fun valueOf(field: FieldSpec): String = values[field.name].orEmpty()

    fun with(field: FieldSpec, value: String): CredentialForm = copy(values = values + (field.name to value))

    fun touch(field: FieldSpec): CredentialForm = copy(touched = touched + field.name)

    /** Every field counts as touched (after the person tries to save). */
    fun touchAll(): CredentialForm = copy(touched = request.fields.map { it.name }.toSet())

    private val cardNumber: String
        get() = request.fields.firstOrNull { it.type == FieldType.CARD_NUMBER }?.let { values[it.name] }.orEmpty()

    /** What is wrong with [field] right now, whether or not it has been touched. */
    fun error(field: FieldSpec): String? =
        Validators.field(field.type, valueOf(field), field.required, nowYear, nowMonth, cardNumber)

    /** The error to show: only once the person has been in the field. */
    fun shownError(field: FieldSpec): String? = if (field.name in touched) error(field) else null

    val valid: Boolean get() = request.fields.all { error(it) == null }

    /** The values as they are sealed: card numbers as digits, expiry as `MM/YY`, phones without punctuation. */
    fun normalized(): Map<String, String> = request.fields.mapNotNull { f ->
        val raw = valueOf(f)
        if (raw.isEmpty()) return@mapNotNull null
        f.name to when (f.type) {
            FieldType.CARD_NUMBER -> Validators.digitsOnly(raw)
            FieldType.CARD_EXPIRY -> normalizeExpiry(raw)
            FieldType.PHONE -> raw.filter { it != ' ' && it != '-' && it != '(' && it != ')' && it != '.' }
            FieldType.PASSWORD -> raw
            else -> raw.trim()
        }
    }.toMap()
}

private val EXPIRY = Regex("^\\s*(\\d{2})\\s*[/-]?\\s*(\\d{2}|\\d{4})\\s*$")

/** `0328`, `03/2028` and `03-28`-style input as `03/28`; anything else unchanged. */
fun normalizeExpiry(value: String): String {
    val m = EXPIRY.matchEntire(value) ?: return value.trim()
    val year = m.groupValues[2].takeLast(2)
    return "${m.groupValues[1]}/$year"
}
