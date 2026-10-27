package net.robog.clarity.backend.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.backend.vault.Placeholders
import net.robog.clarity.backend.vault.Sites

/** Pure rules for the browser tool wrapper: which tools exist for the agent, and which steps need a person. */

/** How the wrapper treats a browser MCP tool, by its remote (unprefixed) name. */
enum class BrowserKind {
    /** Raw scripts, privileged access, preferences, extensions, debugging, recordings: the agent never sees them. */
    HIDDEN,

    /** Reads and tab management; runs freely (results are scrubbed). */
    READ,

    /** Changes the page address; runs freely, and updates the wrapper's idea of the current page. */
    NAVIGATE,

    /** Changes the page's state (click, type, fill, ...): checked before it runs. */
    ACT,

    /** Captures pixels; refused while a filled secret may be on screen. */
    CAPTURE,
}

private val ACT_TOOLS = setOf(
    "click_by_uid", "fill_by_uid", "fill_form_by_uid", "type_text", "press_key", "upload_file_by_uid", "drag_by_uid_to_uid", "accept_dialog",
)
private val NAVIGATE_TOOLS = setOf("navigate_page", "new_page", "navigate_history")
private val CAPTURE_TOOLS = setOf("screenshot_page", "screenshot_by_uid", "print_page")
private val READ_TOOLS = setOf(
    "list_pages", "select_page", "close_page", "get_page_text", "take_snapshot", "resolve_uid_to_selector", "clear_snapshot",
    "hover_by_uid", "list_network_requests", "get_network_request", "set_network_cache", "list_console_messages",
    "clear_console_messages", "list_downloads", "clear_downloads", "set_viewport_size", "get_firefox_info", "get_firefox_output",
    "dismiss_dialog",
)

/** Tools that accept `[secret:...]` placeholders: the ones that put text into a field. */
val TEXT_ENTRY_TOOLS = setOf("fill_by_uid", "fill_form_by_uid", "type_text")

/** Anything not listed is hidden: a tool a newer browser server adds is not used until someone classifies it. */
fun classifyBrowserTool(remoteName: String): BrowserKind = when (remoteName) {
    in ACT_TOOLS -> BrowserKind.ACT
    in NAVIGATE_TOOLS -> BrowserKind.NAVIGATE
    in CAPTURE_TOOLS -> BrowserKind.CAPTURE
    in READ_TOOLS -> BrowserKind.READ
    else -> BrowserKind.HIDDEN
}

/** An element as the last snapshot described it; [href] is where a link goes (the snapshot may shorten it). */
data class Element(val role: String?, val name: String?, val href: String? = null) {
    fun describe(uid: String): String = listOfNotNull(role, name?.let { "\"$it\"" }).joinToString(" ").ifEmpty { "element $uid" }

    /** [describe] plus where a link goes, for the triage. */
    fun detail(uid: String): String = describe(uid) + (href?.let { " linking to $it" } ?: "")
}

private val SNAPSHOT_LINE = Regex("""uid=(\S+)\s+(\S+)(?:\s+"((?:[^"\\]|\\.)*)")?""")
private val HREF = Regex("""\shref="((?:[^"\\]|\\.)*)"""")

/** Pure: uid to role, name and href from `take_snapshot` output (lines like `uid=1_4 button "Send"`). */
fun parseSnapshot(text: String): Map<String, Element> {
    val out = LinkedHashMap<String, Element>()
    text.lineSequence().forEach { line ->
        SNAPSHOT_LINE.find(line)?.let { m ->
            val href = HREF.find(line, m.range.last)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            out[m.groupValues[1]] = Element(m.groupValues[2], m.groups[3]?.value?.replace("\\\"", "\""), href)
        }
    }
    return out
}

/** Pure: a link that runs a script or stays on the page ("#", "javascript:") rather than opening another address. */
fun isScriptHref(href: String): Boolean = href.trim().let { it.startsWith("#") || it.startsWith("javascript:", ignoreCase = true) }

/** Pure: a link element that opens an http(s) address (an ordinary navigation, a GET). */
fun isNavigatingLink(element: Element?): Boolean =
    element != null && element.role?.lowercase() in LINK_ROLES && element.href?.let { h -> !isScriptHref(h) && (h.startsWith("http") || h.startsWith("/")) } == true

private val PAGE_LINE = Regex("""^>\s*\[(\d+)]\s+(.*)$""")

/** Pure: the URL of the selected tab in `list_pages` output (`>[1] Title (https://...)`), or null. */
fun parseSelectedUrl(text: String): String? {
    val line = text.lineSequence().firstNotNullOfOrNull { PAGE_LINE.find(it.trim()) }?.groupValues?.get(2) ?: return null
    val open = line.lastIndexOf(" (")
    if (open < 0 || !line.endsWith(")")) return null
    return line.substring(open + 2, line.length - 1).takeIf { it.isNotBlank() && it != "URL unavailable" }
}

/** Pure: no page: an empty tab, the start page, or unknown. */
fun isBlankPage(url: String?): Boolean = url == null || url.isBlank() || url.trim().lowercase().let { it == "about:blank" || it == "about:newtab" || it == "about:home" }

private val SNAPSHOT_UID = Regex("""uid=\S+""")

/** Pure: a snapshot or page text with nothing on it (an empty tab gives `uid=e0 body` or 0 chars). */
fun looksEmpty(remote: String, content: String): Boolean = when (remote) {
    "take_snapshot" -> SNAPSHOT_UID.findAll(content).count() <= 1
    "get_page_text" -> content.isBlank() || "[full content, 0 chars]" in content
    else -> false
}

/**
 * Pure: what to tell the agent when the selected tab is empty but it was last on [lastPage] (the
 * browser restarted, or the tab was closed), so it says so and goes back instead of assuming.
 */
fun lostPageNote(selectedUrl: String?, lastPage: String?): String? =
    if (isBlankPage(selectedUrl) && !isBlankPage(lastPage)) {
        "Note: the selected tab is empty, but the browser was last on $lastPage (it may have restarted). " +
            "Say so in your report. If list_pages shows that page, select_page it; otherwise navigate_page to it again " +
            "and check where you are (still signed in? same step?) before going on."
    } else {
        null
    }

/** Pure: the element uids an acting call's [input] refers to. */
fun targetUids(input: JsonObject): List<String> = buildList {
    for (key in listOf("uid", "fromUid", "toUid")) (input[key] as? JsonPrimitive)?.contentOrNull?.let(::add)
    (input["elements"] as? JsonArray)?.forEach { e -> ((e as? JsonObject)?.get("uid") as? JsonPrimitive)?.contentOrNull?.let(::add) }
}

/**
 * Pure: the uids a text-entry call types a secret placeholder into (`fill_by_uid`'s uid, the
 * `fill_form_by_uid` elements whose value has one).
 */
fun secretTargets(input: JsonObject): List<String> = buildList {
    val value = input["value"] as? JsonPrimitive
    if (value != null && value.isString && Placeholders.tokens(value.content).isNotEmpty()) (input["uid"] as? JsonPrimitive)?.contentOrNull?.let(::add)
    (input["elements"] as? JsonArray)?.forEach { e ->
        val o = e as? JsonObject ?: return@forEach
        val v = o["value"] as? JsonPrimitive
        if (v != null && v.isString && Placeholders.tokens(v.content).isNotEmpty()) (o["uid"] as? JsonPrimitive)?.contentOrNull?.let(::add)
    }
}

private val VALUE_ATTR = Regex("""(\svalue=)"(?:[^"\\]|\\.)*"""")

/**
 * Pure: [text] (a snapshot) with the `value="…"` of each element in [uids] replaced by a marker.
 * Scrubbing removes released values only when they appear whole; a field that was typed into
 * wrongly (or holds part of one) would otherwise show what is in it.
 */
fun hideFilledValues(text: String, uids: Set<String>): String {
    if (uids.isEmpty() || "uid=" !in text) return text
    return text.lineSequence().joinToString("\n") { line ->
        val uid = SNAPSHOT_LINE.find(line)?.groupValues?.get(1)
        if (uid != null && uid in uids) VALUE_ATTR.replace(line) { "${it.groupValues[1]}\"[secret, hidden]\"" } else line
    }
}

/** Pure: every string the call types (fill value, typed text, form values). */
fun typedStrings(input: JsonObject): List<String> = buildList {
    for (key in listOf("value", "text")) (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::add)
    (input["elements"] as? JsonArray)?.forEach { e -> ((e as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::add) }
}

private val PAYMENT_PATH = Regex("""(^|[/._-])(checkout|payment|payments|pay|billing|cart|basket|order|purchase|buy|donate)([/._?#-]|$)""", RegexOption.IGNORE_CASE)
private val PAYMENT_HOSTS = setOf("paypal.com", "stripe.com", "checkout.com", "klarna.com", "adyen.com", "squareup.com", "venmo.com")

/** Pure: does [url] look like a checkout or payment page? */
fun isPaymentPage(url: String?): Boolean {
    if (url == null) return false
    val host = Sites.host(url) ?: return false
    if (Sites.registrableDomain(host) in PAYMENT_HOSTS) return true
    if (host.startsWith("checkout.") || host.startsWith("pay.") || host.startsWith("payments.")) return true
    val path = runCatching { java.net.URI(url).let { (it.path ?: "") + "?" + (it.query ?: "") } }.getOrDefault("")
    return PAYMENT_PATH.containsMatchIn(path)
}

private val CARD_FIELD = Regex(
    "card ?number|credit card|debit card|cvc|cvv|cvn|security code|expir|card ?holder|name on card|cc-number|cc-csc|cc-exp",
    RegexOption.IGNORE_CASE,
)

/** Pure: is this element a card-detail field? */
fun isCardField(element: Element?): Boolean = element?.name?.let { CARD_FIELD.containsMatchIn(it) } == true

// Phrases that move money, give up an account or commit the person, in any role.
private val STRONG_RISK = Regex(
    "\\b(delete|unsubscribe|pay|pay now|buy|buy now|purchase|place (your |my )?order|complete (your |my )?(order|purchase)|checkout|check out|book now|" +
        "donate|sign[ -]?up|transfer|withdraw|remove account|close account|cancel (my |your )?(subscription|order|account|booking)|confirm (order|payment|purchase)|subscribe)\\b",
    RegexOption.IGNORE_CASE,
)

// Phrases that send or commit, but are ordinary words in links ("Order history", "Send feedback").
private val BUTTON_RISK = Regex(
    "\\b(send|submit|order|book|reserve|confirm|accept|agree|register|publish|post|remove|continue to payment|proceed to payment|authorize|approve)\\b",
    RegexOption.IGNORE_CASE,
)

private val PAYMENT_ACTION = Regex("\\b(pay|payment|place|complete|confirm|submit|continue|review|finish|buy|order|purchase|proceed)\\b", RegexOption.IGNORE_CASE)

private val BUTTON_ROLES = setOf("button", "menuitem", "checkbox", "switch", "submit", "image", "imagebutton", "reset", "menuitemcheckbox")
private val TEXT_ROLES = setOf("textbox", "searchbox", "textarea", "combobox", "input", "spinbutton", "text")
private val LINK_ROLES = setOf("link", "a")
private val PLAIN_ROLES = LINK_ROLES + setOf("tab", "option", "radio", "treeitem", "listitem", "row", "cell", "gridcell", "heading")

/** What the rules say about one acting call. */
sealed interface StepVerdict {
    /** Run it. */
    data object Pass : StepVerdict

    /** Needs the person; [neverAlone] marks payments and card details (the guard treats them as a hard line). */
    data class Ask(val reason: String, val neverAlone: Boolean) : StepVerdict

    /** Not decided by the rules: a fast model check decides. */
    data object Triage : StepVerdict
}

/**
 * Pure: the deterministic half of the browser gate. [elements] are the call's targets as the last
 * snapshot described them (null when unknown); [typed] are the strings it types.
 *
 * - a button-like element whose name says send, submit, pay, buy, book, order, delete, confirm, accept,
 *   sign up, unsubscribe ... asks; on a checkout or payment page almost any step button asks, as payments;
 * - typing a literal into a card field asks as a payment (card values come from a credential's
 *   placeholders, whose release the person already approved on the phone);
 * - Enter / submitKey submits: asks on a payment page, otherwise the triage decides;
 * - typing into an ordinary text field on an ordinary page, and plain links, pass;
 * - plain buttons that only move along or acknowledge ("Continue", "Next", "Sign in", "I understand",
 *   "Accept cookies", "Not now") pass off payment pages;
 * - with [signingIn] (the person released a saved login for this site), the steps of a sign-in page
 *   pass too: choosing where the code goes ("SMS", "Send code"), verifying, "Submit", "Trust this device".
 */
fun stepVerdict(tool: String, input: JsonObject, elements: List<Element?>, pageUrl: String?, signingIn: Boolean = false): StepVerdict {
    val payment = isPaymentPage(pageUrl)
    val typed = typedStrings(input)
    val allPlaceholders = typed.isNotEmpty() && typed.all { t -> Placeholders.tokens(t).isNotEmpty() && Placeholders.replace(t) { "" }.isEmpty() }
    val names = elements.mapNotNull { it?.name }.joinToString(" | ")
    val roles = elements.mapNotNull { it?.role?.lowercase() }

    when (tool) {
        "fill_by_uid", "fill_form_by_uid", "type_text" -> {
            if (elements.any { isCardField(it) } && !allPlaceholders) {
                return StepVerdict.Ask("typing card details into $names", neverAlone = true)
            }
            val submit = (input["submitKey"] as? JsonPrimitive)?.contentOrNull
            if (submit != null && isSubmitKey(submit)) {
                return when {
                    payment -> StepVerdict.Ask("submitting a payment form", neverAlone = true)
                    signingIn && isSignInPage(pageUrl) -> StepVerdict.Pass
                    else -> StepVerdict.Triage
                }
            }
            return if (elements.all { it?.role == null || it.role.lowercase() in TEXT_ROLES }) StepVerdict.Pass else StepVerdict.Triage
        }
        "press_key" -> {
            val key = (input["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return when {
                isSubmitKey(key) && payment -> StepVerdict.Ask("pressing Enter on a payment page", neverAlone = true)
                isSubmitKey(key) -> if (signingIn && isSignInPage(pageUrl)) StepVerdict.Pass else StepVerdict.Triage
                key.lowercase() in SAFE_KEYS -> StepVerdict.Pass
                else -> StepVerdict.Triage
            }
        }
        "click_by_uid" -> {
            val strong = STRONG_RISK.find(names)?.value
            // A link only opens another address ("Book now" -> the page with times): the triage weighs it with the task, unless it moves money.
            val navigating = elements.isNotEmpty() && elements.all { isNavigatingLink(it) }
            if (strong != null && navigating && !payment && !isMoney(strong)) return StepVerdict.Triage
            if (strong != null) return StepVerdict.Ask("clicking \"$names\" ($strong)", neverAlone = isMoney(strong) || payment)
            val labels = elements.mapNotNull { it?.name?.trim() }
            if (!payment && labels.isNotEmpty() && labels.all { isPlainStep(it) || (signingIn && isSignInPage(pageUrl) && isSignInStep(it)) }) return StepVerdict.Pass
            val buttonLike = roles.isEmpty() || roles.any { it in BUTTON_ROLES }
            if (buttonLike) {
                val weak = BUTTON_RISK.find(names)?.value
                if (weak != null) return StepVerdict.Ask("clicking \"$names\" ($weak)", neverAlone = payment)
                if (payment && PAYMENT_ACTION.containsMatchIn(names)) return StepVerdict.Ask("clicking \"$names\" on a payment page", neverAlone = true)
            }
            return when {
                elements.any { e -> e?.href?.let(::isScriptHref) == true } -> StepVerdict.Triage
                roles.isNotEmpty() && roles.all { it in PLAIN_ROLES } -> StepVerdict.Pass
                else -> StepVerdict.Triage
            }
        }
        "upload_file_by_uid", "drag_by_uid_to_uid", "accept_dialog" -> {
            val strong = STRONG_RISK.find(names)?.value
            return if (strong != null) StepVerdict.Ask("\"$names\" ($strong)", neverAlone = isMoney(strong) || payment) else StepVerdict.Triage
        }
    }
    return StepVerdict.Triage
}

// The whole label of a button that only moves along, signs in or acknowledges a notice.
private val PLAIN_STEP = Regex(
    "(continue|next|next step|go|start|get started|sign in|log in|login|sign in with (email|password)|use password|show password|" +
        "i understand|understood|got it|ok|okay|close|dismiss|no,? thanks|no thank you|not now|maybe later|skip|skip for now|remind me later|" +
        "accept( all)? cookies|accept cookies and continue|allow( all)? cookies|reject( all)?( cookies)?|decline( all)?( cookies)?|necessary cookies only|" +
        "manage cookies|cookie settings|back|previous|see (all|more)( times| availability)?|show (all|more)( times)?|load more|view (all|more)|search|apply|filter|" +
        "find a (store|salon|location)|change (store|salon|location)|select (store|salon|location)|choose (store|salon|location))[.!]?",
    RegexOption.IGNORE_CASE,
)

/** Pure: [label] is the whole label of a button that only moves along, signs in or acknowledges a notice. */
fun isPlainStep(label: String): Boolean = PLAIN_STEP.matches(label.trim().replace(Regex("\\s+"), " "))

// The whole label of a sign-in page's own steps: where to send the code, verifying it, keeping the device signed in.
private val SIGN_IN_STEP = Regex(
    "(sms|text|text message|text me|text me a code|call|call me|email|email me|email me a code|phone|send|send code|send (me )?(a |the )?(new )?code|resend|resend code|" +
        "verify|verify code|confirm|confirm code|submit|submit code|done|remember (me|this device|this browser)|trust this (device|browser)|" +
        "don'?t ask again( on this device)?|yes|try another (method|way)|use another (method|way))[.!]?",
    RegexOption.IGNORE_CASE,
)

/** Pure: [label] is one of a sign-in page's own steps (choosing where the code goes, verifying it). */
fun isSignInStep(label: String): Boolean = SIGN_IN_STEP.matches(label.trim().replace(Regex("\\s+"), " "))

private val SIGN_IN_PATH = Regex("""(^|[/._-])(login|log-in|signin|sign-in|sign_in|auth|authorize|oauth|sso|mfa|2fa|otp|verify|verification|challenge|identifier|password|account/login|u/login)([/._?#-]|$)""", RegexOption.IGNORE_CASE)

/** Pure: [url] looks like a sign-in or verification page (its path, or a login/auth/accounts host). */
fun isSignInPage(url: String?): Boolean {
    if (url == null) return false
    val host = Sites.host(url) ?: return false
    if (listOf("login.", "auth.", "accounts.", "account.", "id.", "sso.", "signin.").any { host.startsWith(it) }) return true
    val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
    return SIGN_IN_PATH.containsMatchIn(path)
}

private val SAFE_KEYS = setOf("tab", "escape", "arrowup", "arrowdown", "arrowleft", "arrowright", "pageup", "pagedown", "home", "end", "shift+tab")

private fun isSubmitKey(key: String): Boolean = key.trim().lowercase().let { it == "enter" || it == "return" || it == "numpadenter" }

private fun isMoney(phrase: String): Boolean =
    Regex("pay|buy|purchase|order|checkout|check out|donate|transfer|withdraw|subscri", RegexOption.IGNORE_CASE).containsMatchIn(phrase)

/** Pure: a one-line description of an acting call for the held item and the triage ("Click button \"Send\" on mail.example.com/compose"). */
fun describeStep(tool: String, input: JsonObject, elements: List<Pair<String, Element?>>, pageUrl: String?): String {
    val where = pageUrl?.let { Sites.host(it)?.let { host -> host + (runCatching { java.net.URI(it).path }.getOrNull() ?: "") } } ?: "the current page"
    fun target(i: Int = 0) = elements.getOrNull(i)?.let { (uid, e) -> e?.describe(uid) ?: "element $uid" } ?: "the focused element"
    val text = typedStrings(input).firstOrNull()?.let { if (it.length > 60) it.take(60) + "…" else it }
    return when (tool) {
        "click_by_uid" -> (if ((input["dblClick"] as? JsonPrimitive)?.contentOrNull == "true") "Double-click " else "Click ") + target() + " on $where"
        "fill_by_uid" -> "Fill ${target()} with ${text?.let { "\"$it\"" } ?: "text"} on $where"
        "fill_form_by_uid" -> "Fill in ${elements.size} fields (${elements.joinToString { (uid, e) -> e?.describe(uid) ?: uid }}) on $where"
        "type_text" -> "Type ${text?.let { "\"$it\"" } ?: "text"} into ${target()} on $where" +
            ((input["submitKey"] as? JsonPrimitive)?.contentOrNull?.let { " then press $it" } ?: "")
        "press_key" -> "Press ${(input["key"] as? JsonPrimitive)?.contentOrNull ?: "a key"} on $where"
        "upload_file_by_uid" -> "Upload a file to ${target()} on $where"
        "drag_by_uid_to_uid" -> "Drag ${target(0)} to ${target(1)} on $where"
        "accept_dialog" -> "Accept the dialog on $where"
        else -> "$tool on $where"
    }
}

/** Pure: a JSON value is a string that is exactly one placeholder (used by tests and rules). */
fun isOnlyPlaceholder(element: JsonElement): Boolean =
    element is JsonPrimitive && element.isString && Placeholders.tokens(element.content).size == 1 && Placeholders.replace(element.content) { "" }.isEmpty()

/** Pure: what a fill says when the person has not typed the code it needs after [waited]. */
fun codeNotTyped(waited: kotlin.time.Duration): String =
    "I waited ${waited.inWholeMinutes} minutes and the person has not typed that code yet. Do not try the same call again " +
        "or ask for the code again: report back what you found so far and that the code step is waiting for them."

private val CUT_OFF = listOf(
    "unexpected end of the input", "unexpected json token", "cannot read json element", "end of input", "unterminated", "empty response", "cut off",
)

/** Pure: does a tool error read like a reply that came back empty or cut off (not like the browser refusing the step)? */
fun looksCutOff(message: String): Boolean {
    val m = message.lowercase()
    return m.isBlank() || CUT_OFF.any { it in m }
}

/** Pure: what the agent is told after a step whose reply was cut off, given the page before and after. */
fun cutOffNote(remote: String, before: String?, after: String?): String = buildString {
    append("The ").append(remote).append(" step was sent, but the browser's reply came back cut off (this happens when the page navigates). ")
    when {
        after == null -> append("I could not tell which page is open now. ")
        after != before -> append("The page changed: the selected tab is now ").append(after).append(". ")
        else -> append("The selected tab is still ").append(after).append(". ")
    }
    append("Take a snapshot to see where you are before the next step; do not repeat this one blindly.")
}
