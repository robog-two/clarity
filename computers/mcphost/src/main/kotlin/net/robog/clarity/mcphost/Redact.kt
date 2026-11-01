package net.robog.clarity.mcphost

/**
 * Removes known secret values (tokens, proxy passwords) from text a server produced before it is
 * logged or shown, and keeps it short. Values under 4 characters are not replaced (they would blank
 * out ordinary words and are not worth stealing).
 */
class Redactor(secrets: Collection<String>) {
    private val values = secrets.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }

    fun clean(text: String, max: Int = 500): String {
        var out = text
        for (v in values) out = out.replace(v, "•••")
        // proxy URLs carry a password even when we did not know it (a server's own message)
        out = out.replace(Regex("""(\w+://[^\s:/@]+:)[^\s@/]+@"""), "$1•••@")
        out = out.filter { it == '\t' || !it.isISOControl() }
        return if (out.length > max) out.take(max - 1) + "…" else out
    }
}
