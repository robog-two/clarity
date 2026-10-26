package net.robog.clarity.backend.vault

import com.google.common.net.InternetDomainName
import java.net.IDN
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure helpers for `[secret:<credential>_<field>]` placeholders. The agent writes them; only the
 * browser wrapper ever replaces them with values.
 */
object Placeholders {
    /** `\[secret:x]` is an escaped (literal) placeholder; the token charset has no brackets, so nesting never matches. */
    private val PATTERN = Regex("""(\\?)\[secret:([A-Za-z0-9_.-]{1,160})]""")

    /** The prefix of the pseudo-credential that carries a two-factor code: `[secret:code_<heldId>]`. */
    const val CODE_PREFIX = "code_"

    /** Tokens (the text between `secret:` and `]`) of every unescaped placeholder in [text], in order. */
    fun tokens(text: String): List<String> =
        PATTERN.findAll(text).filter { it.groupValues[1].isEmpty() }.map { it.groupValues[2] }.toList()

    /**
     * Replaces each unescaped placeholder by `value(token)`; a null result leaves it unchanged.
     * Escaped placeholders (`\[secret:x]`) lose the backslash and stay literal. Substituted values are
     * not scanned again, so a value that looks like a placeholder is never expanded.
     */
    fun replace(text: String, value: (String) -> String?): String =
        PATTERN.replace(text) { m ->
            if (m.groupValues[1].isNotEmpty()) m.value.substring(1) else value(m.groupValues[2]) ?: m.value
        }

    /** Every unescaped placeholder token in any string value of [element] (object keys are not searched). */
    fun tokens(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) tokens(element.content) else emptyList()
        is JsonArray -> element.flatMap { tokens(it) }
        is JsonObject -> element.values.flatMap { tokens(it) }
        JsonNull -> emptyList()
    }

    /** [element] with [replace] applied to every string value. */
    fun replace(element: JsonElement, value: (String) -> String?): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(replace(element.content, value)) else element
        is JsonArray -> JsonArray(element.map { replace(it, value) })
        is JsonObject -> JsonObject(element.mapValues { replace(it.value, value) })
        JsonNull -> element
    }

    /** A token resolved to a credential handle and one of its field names. */
    data class Ref(val credential: String, val field: String)

    /**
     * Splits [token] into `<credential>_<field>` against the known credentials (handle to field
     * names). Handles and fields both contain underscores, so the longest handle that is a proper
     * prefix and leaves a known field wins. Null when nothing matches.
     */
    fun resolve(token: String, known: Map<String, Set<String>>): Ref? =
        known.entries
            .filter { (name, fields) -> token.startsWith("${name}_") && token.removePrefix("${name}_") in fields }
            .maxByOrNull { it.key.length }
            ?.let { Ref(it.key, token.removePrefix("${it.key}_")) }

    /** The held id inside a `code_<heldId>` token, or null if [token] is not a code token. */
    fun codeId(token: String): String? = token.takeIf { it.startsWith(CODE_PREFIX) }?.removePrefix(CODE_PREFIX)?.ifEmpty { null }

    fun placeholder(credential: String, field: String): String = "[secret:${credential}_$field]"
}

/** Registrable-domain logic for grants: "the same site" means the same registrable domain, over https. */
object Sites {
    /** The host of [urlOrHost] (a URL or a bare host), lowercased and in ASCII (punycode); null if there is none. */
    fun host(urlOrHost: String): String? {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val host = try {
            URI(candidate).host
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            IDN.toASCII(host.trimEnd('.')).lowercase().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The registrable domain (eTLD+1) of [urlOrHost] by the Public Suffix List, e.g.
     * `login.bank.co.uk` to `bank.co.uk`. Hosts that have none (IP addresses, `localhost`, a bare
     * public suffix such as `co.uk`) are returned whole, so they only ever match themselves.
     */
    fun registrableDomain(urlOrHost: String): String? {
        val host = host(urlOrHost) ?: return null
        if (host.all { it.isDigit() || it == '.' } || ':' in host) return host
        return try {
            val name = InternetDomainName.from(host)
            if (name.isUnderPublicSuffix) name.topPrivateDomain().toString() else host
        } catch (_: IllegalArgumentException) {
            host
        } catch (_: IllegalStateException) {
            host
        }
    }

    /** True when [url] uses https; secrets are never put into plain-http pages. */
    fun isHttps(url: String): Boolean = url.trim().lowercase().startsWith("https://")
}
