package net.robog.clarity.app.data

import java.net.URI
import java.net.URISyntaxException

sealed interface UrlError {
    data object Empty : UrlError
    data object Malformed : UrlError
    /** http:// is only accepted in debug builds. */
    data object InsecureScheme : UrlError
    data object UnsupportedScheme : UrlError
}

sealed interface UrlValidation {
    /** [url] is normalized: lower-case scheme/host, no trailing slash, no query or fragment. */
    data class Valid(val url: String) : UrlValidation
    data class Invalid(val error: UrlError) : UrlValidation
}

/** Pure validation/normalization of the instance URL the user types. */
object InstanceUrl {
    fun validate(raw: String, allowCleartext: Boolean): UrlValidation {
        val text = raw.trim()
        if (text.isEmpty()) return UrlValidation.Invalid(UrlError.Empty)
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return UrlValidation.Invalid(UrlError.Malformed)
        }
        val scheme = uri.scheme?.lowercase()
            ?: return UrlValidation.Invalid(UrlError.UnsupportedScheme)
        when (scheme) {
            "https" -> Unit
            "http" -> if (!allowCleartext) return UrlValidation.Invalid(UrlError.InsecureScheme)
            else -> return UrlValidation.Invalid(UrlError.UnsupportedScheme)
        }
        val host = uri.host
        if (host.isNullOrBlank() || uri.userInfo != null) return UrlValidation.Invalid(UrlError.Malformed)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return UrlValidation.Valid("$scheme://${host.lowercase()}$port$path")
    }
}
