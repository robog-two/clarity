package net.robog.clarity.backend.agent

import kotlin.math.ln

/**
 * Pure: a simple detector for text that looks like a secret (a password, an API key or token, a
 * private key, a one-time code). Memory never keeps such text: a note that matches is rejected,
 * and archived conversation or text sent for embedding has the match replaced by [REDACTED].
 *
 * Deliberately simple and a little eager: a false positive costs one rephrased note, a false
 * negative a secret sitting in searchable memory.
 */
object SecretDetector {
    const val REDACTED = "[redacted]"

    /** [pattern]'s group 1, when present, is the secret value; a match whose value is an ordinary word ([WORDS]) does not count. */
    private data class Rule(val reason: String, val pattern: Regex) {
        fun matches(text: String): Sequence<MatchResult> = pattern.findAll(text).filter { m ->
            val value = m.groups.takeIf { it.size > 1 }?.get(1)?.value ?: return@filter true
            value.lowercase().trimEnd('.', '!', '?') !in WORDS
        }
    }

    /** Words that follow "password is", "token was" in ordinary sentences ("the token is expired"). */
    private val WORDS = setOf(
        "the", "a", "an", "in", "on", "at", "for", "to", "not", "now", "still", "being", "been", "set", "same", "ready",
        "expired", "reset", "changed", "required", "wrong", "incorrect", "saved", "stored", "missing", "needed", "valid",
        "invalid", "correct", "unknown", "empty", "blank", "there", "here", "fine", "ok", "okay", "new", "old", "too",
        "only", "also", "very", "managed", "rotated", "revoked", "sent", "shared", "different", "long", "short", "weak", "strong",
    )

    private val rules = listOf(
        Rule("a private key", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")),
        Rule(
            "an API key or token",
            Regex(
                "\\b(?:sk-(?:ant-|or-|proj-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|" +
                    "glpat-[A-Za-z0-9_-]{16,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|" +
                    "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})",
            ),
        ),
        Rule("a bearer token", Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}")),
        // "password: hunter2", "api key = abc", "my PIN is 1234", "token=..."
        Rule(
            "a password or key",
            Regex(
                "(?i)\\b(?:pass(?:word|wd|phrase|code)?|pwd|pin|api[ _-]?key|secret(?:[ _-]?key)?|access[ _-]?key|" +
                    "(?:auth|access|refresh|session)?[ _-]?token|private[ _-]?key|recovery[ _-]?codes?|backup[ _-]?codes?)" +
                    "\\s*(?:is|was|:|=|->)\\s*[\"']?([^\\s\"',;]{3,})",
            ),
        ),
        // "the code is 482913", "verification code: 4829", "OTP 123456", "2FA code 123-456"
        Rule(
            "a one-time code",
            Regex(
                "(?i)\\b(?:(?<!zip |postal |area |country |dial |source |promo |discount |coupon )code|otp|2fa|mfa|totp|passcode)\\b" +
                    "[^\\n\\d]{0,24}\\b\\d{3}[- ]?\\d{3}\\b|(?i)\\b(?:otp|2fa|mfa|verification|security|login|sign-in|one[- ]time)\\b[^\\n\\d]{0,24}\\b\\d{4,8}\\b",
            ),
        ),
    )

    /** Long runs of key-like characters; checked for mixed case + digits and entropy. */
    private val tokenCandidate = Regex("[A-Za-z0-9_+/=-]{28,}")

    /** Why [text] looks like a secret, or null when it does not. */
    fun reason(text: String): String? {
        rules.firstOrNull { it.matches(text).any() }?.let { return it.reason }
        if (tokenCandidate.findAll(text).any { looksRandom(it.value) }) return "a random-looking key or token"
        return null
    }

    fun looksSecret(text: String): Boolean = reason(text) != null

    /** [text] with every secret-looking span replaced by [REDACTED]. */
    fun redact(text: String): String {
        var out = text
        rules.forEach { rule ->
            val spans = rule.matches(out).map { it.range }.toList()
            spans.asReversed().forEach { out = out.replaceRange(it, REDACTED) }
        }
        return tokenCandidate.replace(out) { if (looksRandom(it.value)) REDACTED else it.value }
    }

    /** Upper, lower and digits mixed, high character entropy: a generated key rather than a word, path or URL slug. */
    private fun looksRandom(s: String): Boolean {
        if (s.none { it.isUpperCase() } || s.none { it.isLowerCase() } || s.none { it.isDigit() }) return false
        // camelCase/kebab words and paths change case rarely; keys change it constantly
        val switches = s.zipWithNext().count { (a, b) -> a.isLetterOrDigit() && b.isLetterOrDigit() && kind(a) != kind(b) }
        if (switches < s.length / 4) return false
        return entropy(s) >= 4.0
    }

    private fun kind(c: Char) = when {
        c.isUpperCase() -> 0
        c.isLowerCase() -> 1
        else -> 2
    }

    private fun entropy(s: String): Double {
        val counts = s.groupingBy { it }.eachCount().values
        return counts.sumOf { n -> val p = n.toDouble() / s.length; -p * ln(p) / ln(2.0) }
    }
}
