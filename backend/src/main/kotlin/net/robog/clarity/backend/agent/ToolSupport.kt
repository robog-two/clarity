package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings

/** The person's settings as the lead needs them. The sessions workstream replaces the default binding. */
fun interface SettingsProvider {
    suspend fun current(): Settings
}

/** The person's home zone: every time an agent sees or gives is on this clock (see [net.robog.clarity.shared.time.LocalTimes]). */
suspend fun SettingsProvider.homeZone(): kotlinx.datetime.TimeZone = net.robog.clarity.shared.time.zoneOrUtc(current().homeZone)

@Inject
@ContributesBinding(AppScope::class)
class DefaultSettingsProvider : SettingsProvider {
    override suspend fun current(): Settings = Settings()
}

/**
 * What one run (the lead thread or a helper) has seen, for the guard's [Provenance]: whether
 * untrusted content is in its current context, and whether the person asked for what it is doing.
 * The lead resets both at the start of every turn; a helper starts tainted if its parent was.
 */
/** [purpose] is what the run was asked to do (a helper's task), shown to the browser triage. */
class RunScope(val runId: String, val isLead: Boolean, untrusted: Boolean = false, val purpose: String? = null) {
    private val untrustedFlag = AtomicBoolean(untrusted)
    private val requestedFlag = AtomicBoolean(false)

    val untrusted: Boolean get() = untrustedFlag.get()
    val requestedByUser: Boolean get() = requestedFlag.get()

    fun taint() = untrustedFlag.set(true)
    fun userAsked() = requestedFlag.set(true)

    /** The lead's current turn: the tasks it touched and the helpers it started (for linking them). */
    val turn = TurnTasks()

    fun startTurn(untrusted: Boolean, requestedByUser: Boolean) {
        untrustedFlag.set(untrusted)
        requestedFlag.set(requestedByUser)
        turn.reset()
    }

    fun provenance() = Provenance(runId, untrustedInput = untrusted, requestedByUser = requestedByUser)
}

/** Tools whose output carries content from outside (mail, contacts' notes, calendar descriptions, search hits). */
val UNTRUSTED_SOURCE_TOOLS = setOf("list_messages", "list_calendar_events", "search_contacts", "search_memory", "read_memory", "read_result")

/** Wraps [tool] so that running it marks [scope] as having seen untrusted content. */
fun Tool.taintingScope(scope: RunScope): Tool {
    val inner = this
    return object : Tool by inner {
        override suspend fun invoke(input: JsonObject): ToolResult {
            val result = inner.invoke(input)
            scope.taint()
            return result
        }
    }
}

/**
 * The idempotency key of an external action: `(runId, toolUseId)` when the harness provides the
 * call (the same key is derived again if a crash makes the run execute the call twice), else a hash
 * of the run, tool and input.
 */
suspend fun idempotencyKey(scope: RunScope, toolName: String, input: Any): String {
    val call = coroutineContext[ToolCallContext]
    if (call != null) return "${call.runId}:${call.toolUseId}"
    val digest = MessageDigest.getInstance("SHA-256").digest("${scope.runId}|$toolName|$input".toByteArray())
    return "${scope.runId}:" + digest.joinToString("") { "%02x".format(it) }.take(24)
}

/** Pure: an email address, phone number or other handle from a plain string like "Dana <dana@x.com>". */
fun parseHandle(raw: String): Handle {
    val trimmed = raw.trim()
    val angle = Regex("^(.*?)\\s*<([^>]+)>$").matchEntire(trimmed)
    val (name, value) = if (angle != null) angle.groupValues[1].trim().trim('"').ifBlank { null } to angle.groupValues[2].trim()
    else null to trimmed
    return when {
        '@' in value -> Handle.email(value.lowercase(), name)
        value.count { it.isDigit() } >= 5 && value.all { it.isDigit() || it in "+-() ." } ->
            Handle.phone("+" + value.filter { it.isDigit() }, name)
        else -> Handle(Handle.Kind.OTHER, value, name)
    }
}

/** Pure: the first `neverStore` topic [fact] touches (case-insensitive substring), if any. */
fun neverStoreTopic(fact: String, neverStore: List<String>): String? {
    val lower = fact.lowercase()
    return neverStore.firstOrNull { topic -> topic.isNotBlank() && lower.contains(topic.trim().lowercase()) }
}

/** Pure: a slice of a stored large result with a note about the rest. */
fun sliceResult(content: String, offset: Int, length: Int): String {
    val start = offset.coerceIn(0, content.length)
    val end = (start + length.coerceAtLeast(1)).coerceAtMost(content.length)
    val rest = content.length - end
    return content.substring(start, end) +
        if (rest > 0) "\n[characters $start-$end of ${content.length}; call again with offset=$end for the next part]" else "\n[end of result, ${content.length} characters]"
}
