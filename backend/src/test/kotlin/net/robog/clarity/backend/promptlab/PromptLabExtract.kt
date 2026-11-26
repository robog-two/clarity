package net.robog.clarity.backend.promptlab

import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.contextSince

/**
 * `:backend:promptlabExtract`: pulls scenarios from the live database (read-only) into [LAB_HOME].
 *
 *     --days N       how far back to look (default 16)
 *     --list         print the candidates and stop
 *     --seqs a,b,c   extract exactly these journal seqs instead of the default mix
 *     --force        overwrite an existing scenarios.md (the old one is kept as scenarios.<time>.bak.md)
 */
object PromptLabExtract {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = Options.parse(args)
        val psql = Psql()
        val now = Clock.System.now()
        val from = now - (opts.days + 2).days
        val runId = opts.run ?: psql.leadRunId()
        val zone = TimeZone.of(psql.homeZone())
        println("lead thread $runId; reading the journal since ${opts.days} days ago (read-only)")
        val journal = psql.journalFrom(runId, from)
        val pairs = journal.map { it.seq to it.message }
        val times = journal.associate { it.seq to it.at }
        val candidates = candidatesIn(pairs, times, now - opts.days.days, ::contextSince)
        println("${journal.size} messages, ${candidates.size} send_to_user moments")
        candidates.groupBy { it.type }.forEach { (t, l) -> println("  ${t.name.padEnd(14)} ${l.size}") }
        if (opts.list) {
            candidates.forEach { println("${it.seq} ${local(it.at, zone)} ${it.type.slug} ${it.sessionId?.take(4).orEmpty()} | ${it.snippet}") }
            return
        }
        val picked: List<Candidate>
        if (opts.seqs != null) {
            picked = opts.seqs.map { s -> candidates.firstOrNull { it.seq == s } ?: error("seq $s is not a send_to_user moment in range") }
        } else {
            val selection = selectScenarios(candidates, recentSince = now - 14.days)
            picked = selection.picked
            selection.shortfalls.forEach { (t, n) -> println("MISSING ${t.name}: $n short of the quota") }
        }

        val constitutions = psql.constitutions()
        val mail = psql.mailAccounts()
        val views = ArrayList<ScenarioView>()
        val scenariosFile = LAB_HOME.resolve("scenarios.md")
        if (scenariosFile.exists() && !opts.force) {
            System.err.println("$scenariosFile exists: pass --force to replace it (the old one is kept as a .bak.md)")
            exitProcess(2)
        }
        if (scenariosFile.exists()) {
            val stamp = Clock.System.now().toString().replace(":", "")
            writePrivate(LAB_HOME.resolve("scenarios.$stamp.bak.md"), scenariosFile.readText())
        }
        // Old scenario folders go away only with an explicit --force; a fresh numbering never mixes with stale folders.
        if (opts.force) scenarioDirs().forEach { dir -> dir.toFile().deleteRecursively() }

        picked.forEachIndexed { index, c ->
            val n = index + 1
            val at = times.getValue(c.seq)
            val i = pairs.indexOfFirst { it.first == c.seq }
            val message = pairs[i].second
            val send = message.content.filterIsInstance<Block.ToolUse>().first { it.name == SEND_TO_USER }
            val messages = contextSince(pairs.subList(0, i).map { it.second })
            val constitution = constitutionAt(constitutions, at)
            val input = ScenarioInput(
                n = n,
                type = c.type.name,
                runId = runId,
                seq = c.seq,
                timestamp = at.toString(),
                homeZone = zone.id,
                constitutionVersion = constitution?.version,
                constitution = constitution?.document,
                mailAccounts = mail,
                messages = messages,
                original = send.input,
                originalOtherTools = message.content.filterIsInstance<Block.ToolUse>().filter { it !== send }.map { it.name },
            )
            val slug = "${id2(n)}-${c.type.slug}-${local(at, zone).take(5).replace("-", "")}"
            writePrivate(LAB_HOME.resolve("scenarios/$slug/input.json"), LabJson.encodeToString(ScenarioInput.serializer(), input))
            views += ScenarioView(n, c.type, local(at, zone), constitution?.version, renderGiven(messages), renderCards(send.input))
            println("${id2(n)} ${c.type.slug.padEnd(10)} ${local(at, zone)} seq=${c.seq} context=${messages.size} messages")
        }
        writePrivate(scenariosFile, scenariosMarkdown(views))
        println("wrote ${views.size} scenarios to ${LAB_HOME.resolve("scenarios")} and $scenariosFile")
    }

    private fun local(at: Instant, zone: TimeZone): String {
        val t = at.toLocalDateTime(zone)
        return "%02d-%02d %02d:%02d".format(t.month.ordinal + 1, t.day, t.hour, t.minute)
    }

    private data class Options(val days: Int, val list: Boolean, val seqs: List<Int>?, val force: Boolean, val run: String?) {
        companion object {
            fun parse(args: Array<String>): Options {
                fun value(flag: String) = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                return Options(
                    days = value("--days")?.toInt() ?: 16,
                    list = "--list" in args,
                    seqs = value("--seqs")?.split(',')?.map { it.trim().toInt() },
                    force = "--force" in args,
                    run = value("--run"),
                )
            }
        }
    }
}
