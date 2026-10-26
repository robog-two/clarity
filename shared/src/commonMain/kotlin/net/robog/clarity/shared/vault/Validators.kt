package net.robog.clarity.shared.vault

/**
 * Pure field validators used by the phone's form and the backend's metadata sanity checks.
 * Each returns null when the value is acceptable, else a short message for the person.
 */
object Validators {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@.]{2,}$")
    private val E164ISH = Regex("^\\+?[0-9]{7,15}$")
    private val EXPIRY = Regex("^\\s*(\\d{2})\\s*[/-]?\\s*(\\d{2}|\\d{4})\\s*$")

    fun digitsOnly(value: String): String = value.filter { it.isDigit() }

    /** Luhn checksum over [number] (spaces and dashes are ignored). */
    fun luhn(number: String): Boolean {
        val cleaned = number.filter { it != ' ' && it != '-' }
        if (cleaned.length !in 12..19 || !cleaned.all { it.isDigit() }) return false
        var sum = 0
        cleaned.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** `visa`, `mastercard`, `amex`, `discover` or `unknown`, from the number's prefix. */
    fun cardBrand(number: String): String {
        val d = digitsOnly(number)
        val p2 = d.take(2).toIntOrNull() ?: 0
        val p4 = d.take(4).toIntOrNull() ?: 0
        return when {
            d.startsWith("4") -> "visa"
            p2 in 51..55 || p4 in 2221..2720 -> "mastercard"
            p2 == 34 || p2 == 37 -> "amex"
            d.startsWith("6011") || d.startsWith("65") || p2 == 64 -> "discover"
            else -> "unknown"
        }
    }

    fun cardNumber(value: String): String? = if (luhn(value)) null else "That card number doesn't look right."

    /** Accepts `MM/YY`, `MM/YYYY`, `MMYY` or `MM-YY`; valid when the month is real and the card is not expired at ([nowYear], [nowMonth]). */
    fun cardExpiry(value: String, nowYear: Int, nowMonth: Int): String? {
        val m = EXPIRY.matchEntire(value) ?: return "Use MM/YY."
        val month = m.groupValues[1].toInt()
        val rawYear = m.groupValues[2]
        val year = if (rawYear.length == 2) 2000 + rawYear.toInt() else rawYear.toInt()
        if (month !in 1..12) return "That month isn't valid."
        if (year < nowYear || (year == nowYear && month < nowMonth)) return "That card has expired."
        return null
    }

    /** CVC length by brand: 4 for American Express, 3 for other known brands; 3 or 4 when [cardNumber] is blank or unknown. */
    fun cvc(value: String, cardNumber: String = ""): String? {
        if (value.isEmpty() || !value.all { it.isDigit() }) return "Digits only."
        return when (cardBrand(cardNumber)) {
            "amex" -> if (value.length == 4) null else "The security code has 4 digits."
            "unknown" -> if (value.length in 3..4) null else "The security code has 3 or 4 digits."
            else -> if (value.length == 3) null else "The security code has 3 digits."
        }
    }

    fun email(value: String): String? = if (EMAIL.matches(value.trim())) null else "That email address doesn't look right."

    fun phone(value: String): String? {
        val cleaned = value.filter { it != ' ' && it != '-' && it != '(' && it != ')' && it != '.' }
        return if (E164ISH.matches(cleaned)) null else "Use a full phone number, e.g. +14155550123."
    }

    fun otp(value: String): String? {
        val v = value.trim()
        return if (v.length in 4..10 && v.all { it.isLetterOrDigit() }) null else "Enter the code exactly as sent."
    }

    /** Validates [value] for a field of [type]; empty required values fail, empty optional ones pass. */
    fun field(
        type: FieldType,
        value: String,
        required: Boolean = true,
        nowYear: Int = 2000,
        nowMonth: Int = 1,
        cardNumber: String = "",
    ): String? {
        if (value.isBlank()) return if (required) "Required." else null
        return when (type) {
            FieldType.EMAIL -> email(value)
            FieldType.PHONE -> phone(value)
            FieldType.CARD_NUMBER -> cardNumber(value)
            FieldType.CARD_EXPIRY -> cardExpiry(value, nowYear, nowMonth)
            FieldType.CARD_CVC -> cvc(value, cardNumber)
            FieldType.OTP -> otp(value)
            FieldType.USERNAME, FieldType.PASSWORD, FieldType.NAME, FieldType.ADDRESS, FieldType.TEXT -> null
        }
    }
}
