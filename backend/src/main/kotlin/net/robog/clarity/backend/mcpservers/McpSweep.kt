package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.robog.clarity.backend.agent.HelperManager
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.JobSlots
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.ScheduledJob
import net.robog.clarity.backend.sessions.localDate
import net.robog.clarity.backend.sessions.nextSweepAfter
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.session.Settings

private val log = KotlinLogging.logger {}

/** The note in a server's collection that says how the sweep finds what is coming up there. */
const val SWEEP_RECIPE = "Sweep recipe:"

/** Pure: the sweep helper's task over [servers], for the 7 days from [from]. */
fun sweepTask(servers: List<McpServer>, from: LocalDate): String {
    val until = from.plus(DatePeriod(days = 7))
    val list = servers.sortedBy { it.label.lowercase() }.joinToString("; ") { s ->
        val reads = s.tools.count { it.mode == McpToolMode.AUTO }
        "${s.label} (notes: collection ${s.collection ?: serverCollectionName(s.host)}; $reads read tools)"
    }
    return "Find what is coming up for the person from $from to $until in their MCP servers: $list. " +
        "You have only tools that read, and $MCP_SWEEP_MAX_TOOL_CALLS tool calls in all, so plan them.\n" +
        "1. Open each server's collection with open_collection. When it has a note starting \"$SWEEP_RECIPE\", follow it.\n" +
        "2. Look for what is due, scheduled or waiting on the person in that window: deadlines, assignments, meetings, reviews or replies they owe, tasks assigned to them. " +
        "Leave out what is done or already past.\n" +
        "3. When you found a quicker way than the recipe (which tool, which arguments), save it as one note starting \"$SWEEP_RECIPE\" " +
        "in that server's collection with save_note, editing the old recipe by its id instead of adding a second one.\n" +
        "4. Finish with one set_scratch call: one line per item, most pressing first, each line starting with its server's name and saying when, " +
        "e.g. \"Canvas: Essay 2 due Thu 23:59 (ENGL 201)\"; at most $COMING_UP_MAX_CHARS characters. A server with nothing coming up gets one line, " +
        "e.g. \"GitHub: nothing due\".\n" +
        "Then reply with one line: how many items you found."
}

/**
 * On call days, [net.robog.clarity.backend.sessions.MCP_SWEEP_LEAD] before the call prep: one
 * cheap helper reads what is coming up in the person's servers (only their `auto` tools) and writes
 * it to the `coming-up` scratch note. Skipped when no enabled server has a tool that runs without
 * asking. It never posts to the chat and wakes nobody; at most one per slot ([JobSlots]).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class McpSweepJob(
    private val kit: McpToolKit,
    private val helpers: HelperManager,
    private val slots: JobSlots,
) : ScheduledJob {
    override val name = MCP_SWEEP_PROFILE

    override fun schedule(settings: Settings) = Schedule.Computed { after, zone ->
        nextSweepAfter(settings.morningCall, after, zone) ?: (after + 1.days)
    }

    override suspend fun fire(context: FireContext) {
        if (!context.settings.morningCall.enabled) return
        val servers = kit.sweepable()
        if (servers.isEmpty()) return
        if (!slots.claim(name, context.scheduledFor)) {
            log.info { "MCP sweep for ${context.scheduledFor} already started; not starting another" }
            return
        }
        helpers.start(
            task = sweepTask(servers, localDate(context.now, context.settings.homeZone)),
            tools = null,
            role = "helper",
            parent = RunScope(MCP_SWEEP_PROFILE, isLead = false),
            profile = MCP_SWEEP_PROFILE,
            serverIds = servers.map { it.id },
        ).onLeft { log.warn { "MCP sweep did not start: $it" } }
    }
}
