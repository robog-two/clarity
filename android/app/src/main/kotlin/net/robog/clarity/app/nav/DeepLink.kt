package net.robog.clarity.app.nav

import net.robog.clarity.shared.governance.HeldActionId

/** Where a notification (or a link) sends the person. */
sealed interface DeepLink {
    data object Today : DeepLink
    data object Ask : DeepLink
    /** The held list. */
    data object Held : DeepLink
    /** One held item: opens its draft review. */
    data class HeldItem(val id: HeldActionId) : DeepLink
    /** The morning call; [answer] answers it straight away (the notification's answer button). */
    data class Call(val answer: Boolean = false) : DeepLink
}

const val LINK_SCHEME = "clarity"

/** `clarity://today`, `clarity://ask`, `clarity://held`, `clarity://held/<id>`, `clarity://call[?answer=1]`. */
fun parseDeepLink(uri: String?): DeepLink? {
    if (uri == null || !uri.startsWith("$LINK_SCHEME://")) return null
    val rest = uri.removePrefix("$LINK_SCHEME://")
    val path = rest.substringBefore('?').trim('/')
    val query = rest.substringAfter('?', "")
    val parts = path.split('/').filter { it.isNotEmpty() }
    return when (parts.firstOrNull()) {
        "today" -> DeepLink.Today
        "ask" -> DeepLink.Ask
        "held" -> parts.getOrNull(1)?.let { DeepLink.HeldItem(HeldActionId(it)) } ?: DeepLink.Held
        "call" -> DeepLink.Call(answer = query.split('&').contains("answer=1"))
        else -> null
    }
}

fun DeepLink.toUri(): String = when (this) {
    DeepLink.Today -> "$LINK_SCHEME://today"
    DeepLink.Ask -> "$LINK_SCHEME://ask"
    DeepLink.Held -> "$LINK_SCHEME://held"
    is DeepLink.HeldItem -> "$LINK_SCHEME://held/${id.value}"
    is DeepLink.Call -> "$LINK_SCHEME://call" + if (answer) "?answer=1" else ""
}
