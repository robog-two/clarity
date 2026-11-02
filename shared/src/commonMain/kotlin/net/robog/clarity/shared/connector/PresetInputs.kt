package net.robog.clarity.shared.connector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The short form of a collection preset (`x-presets[].inputs` and `x-presets[].derive`, see
 * `docs/workstreams/connector-accounts.md`): a few questions the person answers, and templates that
 * turn the answers into item values. The preset's `values` hold everything else.
 *
 * Shared by the connector that declares it (which also tests it) and the app that renders it.
 */
enum class PresetInputType(val wire: String) {
    /** A web address; normalized to `https://<host><path>` and also offered as `{id.host}`. */
    URL("url"),

    /** One of [PresetInput.choices], or free text when [PresetInput.other]. */
    CHOICE("choice"),

    /** An item secret, bound to [PresetInput.property]. Never templated. */
    SECRET("secret"),
    TEXT("text"),
    ;

    companion object {
        fun parse(raw: String?): PresetInputType? = entries.firstOrNull { it.wire == raw }
    }
}

data class PresetChoice(val value: String, val title: String)

data class PresetInput(
    val id: String,
    val title: String,
    val type: PresetInputType,
    val description: String? = null,
    val required: Boolean = false,
    val default: String? = null,
    /** `examples[0]`. */
    val placeholder: String? = null,
    val choices: List<PresetChoice> = emptyList(),
    /** [PresetInputType.CHOICE]: something else may be typed ("Other"). */
    val other: Boolean = false,
    /** [PresetInputType.URL]: the path the address always ends in (`/api/v1`); what was typed after the host is replaced by it. */
    val path: String = "",
    /** Typed text ([PresetInputType.TEXT], or an "Other" choice) must match this whole. */
    val pattern: String? = null,
    /** Bound straight to this item property (always for [PresetInputType.SECRET]); no templates. */
    val property: String? = null,
)

/**
 * [inputs] and the templates over them: `{id}` is an input's value, `{id.host}` a URL input's host.
 * A template naming an input that has no valid value yet produces nothing.
 */
data class PresetForm(
    val inputs: List<PresetInput>,
    /** Scalar item property → template. */
    val scalars: Map<String, String> = emptyMap(),
    /** List item property → one template per entry. */
    val lists: Map<String, List<String>> = emptyMap(),
) {
    /** Item properties the short form sets: through templates, or bound to an input. */
    val targets: Set<String> get() = scalars.keys + lists.keys + inputs.mapNotNull { it.property }

    /** The `inputs` and `derive` members of a preset object. */
    fun toJson(): Map<String, JsonElement> = mapOf(
        "inputs" to JsonArray(inputs.map { it.toJson() }),
        "derive" to JsonObject(
            scalars.mapValues { JsonPrimitive(it.value) } +
                lists.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) },
        ),
    )

    companion object {
        /** The short form of [preset], or null when it has no usable inputs. */
        fun parse(preset: JsonObject): PresetForm? {
            val inputs = (preset["inputs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseInput) }
            if (inputs.isEmpty()) return null
            val derive = preset["derive"] as? JsonObject
            val scalars = linkedMapOf<String, String>()
            val lists = linkedMapOf<String, List<String>>()
            derive?.forEach { (key, e) ->
                when (e) {
                    is JsonArray -> lists[key] = e.mapNotNull { it.str() }
                    is JsonPrimitive -> e.str()?.let { scalars[key] = it }
                    else -> Unit
                }
            }
            return PresetForm(inputs, scalars, lists)
        }

        private fun parseInput(o: JsonObject): PresetInput? {
            val id = o["id"].str()?.takeIf { ID.matches(it) } ?: return null
            val type = PresetInputType.parse(o["type"].str()) ?: return null
            val property = o["property"].str()
            // A secret is only ever stored in its own item property, never written into a template.
            if (type == PresetInputType.SECRET && property == null) return null
            return PresetInput(
                id = id,
                title = o["title"].str() ?: id,
                type = type,
                description = o["description"].str(),
                required = (o["required"] as? JsonPrimitive)?.booleanOrNull == true,
                default = o["default"].str(),
                placeholder = (o["examples"] as? JsonArray)?.firstOrNull().str(),
                choices = (o["choices"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val value = co["value"].str() ?: return@mapNotNull null
                    PresetChoice(value, co["title"].str() ?: value)
                },
                other = (o["other"] as? JsonPrimitive)?.booleanOrNull == true,
                path = o["path"].str().orEmpty(),
                pattern = o["pattern"].str(),
                property = property,
            )
        }

        private val ID = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }
}

private fun PresetInput.toJson(): JsonObject = buildJsonObject {
    put("id", id); put("title", title); put("type", type.wire)
    description?.let { put("description", it) }
    if (required) put("required", true)
    default?.let { put("default", it) }
    placeholder?.let { put("examples", buildJsonArray { add(JsonPrimitive(it)) }) }
    if (choices.isNotEmpty()) {
        put("choices", JsonArray(choices.map { c -> buildJsonObject { put("value", c.value); put("title", c.title) } }))
    }
    if (other) put("other", true)
    if (path.isNotEmpty()) put("path", path)
    pattern?.let { put("pattern", it) }
    property?.let { put("property", it) }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content

/** Why an answer is not usable yet. */
enum class PresetInputError {
    Required,

    /** A URL with another scheme than https (`http://...`). */
    NotHttps,

    /** Not a host name (or has a user name, or is an IP address). */
    NotAnAddress,

    /** A URL on another port than 443. */
    WrongPort,

    /** Typed text that does not match [PresetInput.pattern]. */
    NoMatch,

    /** Not one of the choices, and the input takes no other answer. */
    NotAChoice,
}

/** A normalized web address: `https://<host><path>`. */
data class PresetUrl(val url: String, val host: String)

/** The item properties a short form writes: scalar texts and string lists. */
data class PresetTargets(val texts: Map<String, String> = emptyMap(), val lists: Map<String, List<String>> = emptyMap())

/** What a [PresetForm] produces for some answers; null where an answer is still missing. */
data class PresetDerived(val texts: Map<String, String?>, val lists: Map<String, List<String?>>)

/**
 * Pure logic of the short form. Answers are keyed by input id; a property-bound input's answer lives
 * in its item property instead and is never passed here.
 */
object PresetInputs {
    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*(\\.host)?)\\}")

    /**
     * Accepts `canvas.school.edu`, `https://canvas.school.edu`, or a pasted address with a path
     * (`https://canvas.school.edu/courses/12`) and gives `https://canvas.school.edu<path>`. Only https on
     * the default port; no user names; host names only (not IP addresses).
     */
    fun normalizeUrl(raw: String, path: String = ""): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid(PresetInputError.Required)
        val schemeEnd = text.indexOf("://")
        val rest = if (schemeEnd >= 0) {
            val scheme = text.substring(0, schemeEnd).lowercase()
            if (scheme != "https") return Result.Invalid(if (scheme.isEmpty()) PresetInputError.NotAnAddress else PresetInputError.NotHttps)
            text.substring(schemeEnd + 3)
        } else {
            text
        }
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if ('@' in authority || authority.any { it.isWhitespace() } || authority.startsWith("[")) return Result.Invalid(PresetInputError.NotAnAddress)
        val colon = authority.lastIndexOf(':')
        val hostPart = if (colon >= 0) authority.substring(0, colon) else authority
        if (colon >= 0) {
            val port = authority.substring(colon + 1)
            if (port.isEmpty() || !port.all(Char::isDigit)) return Result.Invalid(PresetInputError.NotAnAddress)
            if (port.toIntOrNull() != 443) return Result.Invalid(PresetInputError.WrongPort)
        }
        val host = hostPart.lowercase().removeSuffix(".")
        val labels = host.split('.')
        val isHost = host.length <= 253 && labels.size >= 2 && labels.all { HOST_LABEL.matches(it) } &&
            !labels.last().all(Char::isDigit)
        if (!isHost) return Result.Invalid(PresetInputError.NotAnAddress)
        val suffix = if (path.isEmpty()) "" else "/" + path.trim('/')
        return Result.Ok(PresetUrl("https://$host$suffix", host))
    }

    sealed interface Result {
        data class Ok(val value: PresetUrl) : Result
        data class Invalid(val error: PresetInputError) : Result
    }

    /** Why [input]'s [answer] is not usable, or null. [hasProperty] says whether a bound property has a value (typed or stored). */
    fun error(input: PresetInput, answer: String, hasProperty: (String) -> Boolean = { false }): PresetInputError? {
        input.property?.let { return if (input.required && !hasProperty(it)) PresetInputError.Required else null }
        val text = answer.trim()
        if (text.isEmpty()) return if (input.required) PresetInputError.Required else null
        return when (input.type) {
            PresetInputType.URL -> (normalizeUrl(text, input.path) as? Result.Invalid)?.error
            PresetInputType.CHOICE -> when {
                input.choices.any { it.value == text } -> null
                !input.other -> PresetInputError.NotAChoice
                else -> patternError(input, text)
            }
            PresetInputType.TEXT -> patternError(input, text)
            PresetInputType.SECRET -> null
        }
    }

    private fun patternError(input: PresetInput, text: String): PresetInputError? =
        input.pattern?.let { p -> if (runCatching { Regex(p).matches(text) }.getOrDefault(false)) null else PresetInputError.NoMatch }

    /** Every answer that is not usable, by input id. */
    fun errors(form: PresetForm, answers: Map<String, String>, hasProperty: (String) -> Boolean): Map<String, PresetInputError> =
        form.inputs.mapNotNull { i -> error(i, answers[i.id].orEmpty(), hasProperty)?.let { i.id to it } }.toMap()

    /** The template variables of usable answers: `{id}`, and `{id.host}` for a URL. */
    fun variables(form: PresetForm, answers: Map<String, String>): Map<String, String> = buildMap {
        for (input in form.inputs) {
            if (input.property != null) continue
            val text = answers[input.id].orEmpty().trim()
            if (text.isEmpty() || error(input, text) != null) continue
            when (input.type) {
                PresetInputType.URL -> (normalizeUrl(text, input.path) as? Result.Ok)?.value?.let {
                    put(input.id, it.url); put("${input.id}.host", it.host)
                }
                else -> put(input.id, text)
            }
        }
    }

    /** [template] with its placeholders filled in, or null when one of them has no value. */
    fun fill(template: String, variables: Map<String, String>): String? {
        var missing = false
        val out = PLACEHOLDER.replace(template) { m -> variables[m.groupValues[1]] ?: run { missing = true; "" } }
        return if (missing) null else out
    }

    fun derive(form: PresetForm, answers: Map<String, String>): PresetDerived {
        val vars = variables(form, answers)
        return PresetDerived(
            texts = form.scalars.mapValues { fill(it.value, vars) },
            lists = form.lists.mapValues { (_, templates) -> templates.map { fill(it, vars) } },
        )
    }

    /**
     * [current] after the answers change from [before] to [after]. A derived value is only rewritten
     * while it is still what [before] produced (or is not there at all, and nothing like it either): a
     * value changed by hand in the full form stays as it is.
     */
    fun apply(form: PresetForm, before: Map<String, String>, after: Map<String, String>, current: PresetTargets): PresetTargets {
        val old = derive(form, before)
        val new = derive(form, after)
        val texts = current.texts.toMutableMap()
        for (key in form.scalars.keys) {
            val was = old.texts[key].orEmpty()
            val now = new.texts[key].orEmpty()
            val have = texts[key].orEmpty()
            if (was == now) continue
            if (have == was || have.isEmpty()) texts[key] = now
        }
        val lists = current.lists.toMutableMap()
        for ((key, templates) in form.lists) {
            val entries = lists[key].orEmpty().toMutableList()
            templates.forEachIndexed { i, template ->
                val was = old.lists[key]?.get(i)
                val now = new.lists[key]?.get(i)
                if (was == now) return@forEachIndexed
                val at = was?.let(entries::indexOf) ?: -1
                when {
                    at >= 0 -> if (now == null) entries.removeAt(at) else entries[at] = now
                    // Nothing produced yet: add it, unless an entry of the same shape was set by hand.
                    was == null && now != null && now !in entries && entries.none { sameShape(template, it) } -> entries += now
                    else -> Unit
                }
            }
            lists[key] = entries.distinct()
        }
        return PresetTargets(texts, lists)
    }

    /** True if [entry] starts with [template]'s fixed text before its first placeholder (`CANVAS_API_URL=`). */
    private fun sameShape(template: String, entry: String): Boolean {
        val prefix = template.substringBefore('{')
        return prefix.isNotEmpty() && prefix != template && entry.startsWith(prefix)
    }

    /**
     * The answers that would have produced [values], for reopening the short form of a saved item:
     * each input is read back from the first template that uses it alone. Not found: empty.
     */
    fun recover(form: PresetForm, values: PresetTargets): Map<String, String> = buildMap {
        for (input in form.inputs) {
            if (input.property != null) continue
            val found = readBack(form, values, "{${input.id}}")
                ?: if (input.type == PresetInputType.URL) readBack(form, values, "{${input.id}.host}") else null
            put(input.id, found.orEmpty())
        }
    }

    private fun readBack(form: PresetForm, values: PresetTargets, placeholder: String): String? {
        fun match(template: String, value: String): String? {
            if (PLACEHOLDER.findAll(template).count() != 1 || placeholder !in template) return null
            val prefix = template.substringBefore(placeholder)
            val suffix = template.substringAfter(placeholder)
            if (value.length < prefix.length + suffix.length || !value.startsWith(prefix) || !value.endsWith(suffix)) return null
            return value.substring(prefix.length, value.length - suffix.length).takeIf { it.isNotBlank() }
        }
        for ((key, template) in form.scalars) match(template, values.texts[key].orEmpty())?.let { return it }
        for ((key, templates) in form.lists) {
            for (template in templates) {
                for (entry in values.lists[key].orEmpty()) match(template, entry)?.let { return it }
            }
        }
        return null
    }

    /** Item properties whose derived value for [answers] is not what they hold: changed in the full form. */
    fun diverged(form: PresetForm, answers: Map<String, String>, values: PresetTargets): Set<String> {
        val d = derive(form, answers)
        val texts = d.texts.filter { (k, v) -> v != null && values.texts[k].orEmpty() != v }.keys
        val lists = d.lists.filter { (k, entries) -> entries.any { it != null && it !in values.lists[k].orEmpty() } }.keys
        return texts + lists
    }
}
