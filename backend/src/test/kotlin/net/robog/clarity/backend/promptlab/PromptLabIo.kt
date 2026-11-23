package net.robog.clarity.backend.promptlab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Message

/*
 * The prompt lab's edges: reading the live database (read-only, through psql in the podman container), the private data
 * folder, and the OpenRouter key.
 */

/** Where every extracted file lives: outside the repo, owner-only. */
val LAB_HOME: Path get() = Path(System.getenv("CLARITY_PROMPTLAB_HOME") ?: (System.getProperty("user.home") + "/clarity-promptlab"))

/** The home zone assumed when settings do not say. */
const val DEFAULT_HOME_ZONE = "America/New_York"

@Serializable
data class MailAccount(val label: String, val address: String)

/** One extracted scenario: everything the lead was given just before it called `send_to_user`. */
@Serializable
data class ScenarioInput(
    val n: Int,
    val type: String,
    val runId: String,
    val seq: Int,
    val timestamp: String,
    val homeZone: String,
    val constitutionVersion: Int?,
    /** The constitution document live at [timestamp] (as stored); null before the values interview. */
    val constitution: JsonElement?,
    val mailAccounts: List<MailAccount>,
    /** The context since the latest compaction point, as the lead's request would carry it. */
    val messages: List<Message>,
    /** The input of the original `send_to_user` call. */
    val original: JsonObject,
    /** Other tools the original assistant message called alongside. */
    val originalOtherTools: List<String>,
)

val LabJson = Json(from = HarnessJson) { prettyPrint = true }

/** Writes [text] to [path] owner-only (600), creating the owner-only (700) parent folders. */
fun writePrivate(path: Path, text: String) {
    privateDirs(path.parent)
    if (!path.exists()) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(path, text)
}

fun privateDirs(dir: Path) {
    if (dir.exists()) return
    privateDirs(dir.parent ?: return)
    dir.createDirectories(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
}

/** Pure: read-only SQL only (one statement starting with select or with); anything else is refused before it reaches psql. */
fun requireReadOnlySql(sql: String): String {
    val s = sql.trim().trimEnd(';')
    require(Regex("^(select|with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) { "only select queries are allowed" }
    require(';' !in s) { "one statement only" }
    require(!Regex("\\b(insert|update|delete|drop|alter|create|truncate|grant)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s.replace(Regex("'[^']*'"), "''"))) { "write keyword in query" }
    return s
}

/** Reads the production database through `psql` inside its container. Every session is read-only. */
class Psql(private val container: String = System.getenv("PROMPTLAB_PG_CONTAINER") ?: "clarity_postgres_1") {
    /** The lines [sql] prints. */
    fun lines(sql: String): List<String> {
        val checked = requireReadOnlySql(sql)
        val process = ProcessBuilder(
            "podman", "exec", "-i", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
            container, "psql", "-U", "clarity", "-d", "clarity", "-At", "-q", "-v", "ON_ERROR_STOP=1",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.bufferedWriter().use { it.write("SET default_transaction_read_only = on;\n$checked;\n") }
        val out = process.inputStream.bufferedReader().readLines()
        check(process.waitFor() == 0) { "psql failed" }
        return if (out.firstOrNull() == "SET") out.drop(1) else out
    }
}

data class JournalRow(val seq: Int, val at: Instant, val message: Message)

data class ConstitutionRow(val version: Int, val at: Instant, val document: JsonElement)

private val UUID = Regex("^[0-9a-f-]{36}$")
private val ISO = Regex("^[0-9T:.+Z-]+$")

/** The lead thread: the run with the most journal messages. */
fun Psql.leadRunId(): String = lines("select run_id from agent_journal group by 1 order by count(*) desc limit 1").single()

/** The journal of [runId] from the last client summary at or before [from] on (what a scenario at [from] or later can see). */
fun Psql.journalFrom(runId: String, from: Instant): List<JournalRow> {
    require(UUID.matches(runId)) { "bad run id" }
    require(ISO.matches(from.toString())) { "bad time" }
    val sql = """
        with s as (select coalesce(max(seq), 0) as s from agent_journal
                   where run_id = '$runId' and created_at <= '$from' and message->>'role' = 'USER'
                     and message->'content'->0->>'text' like '[clarity:summary]%')
        select json_build_object('seq', seq,
                                 'at', to_char(created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                                 'message', message)::text
        from agent_journal, s where run_id = '$runId' and seq >= s.s order by seq
    """.trimIndent()
    return lines(sql).filter { it.isNotBlank() }.map { line ->
        val o = HarnessJson.parseToJsonElement(line).jsonObject
        JournalRow(
            o["seq"]!!.jsonPrimitive.content.toInt(),
            Instant.parse(o["at"]!!.jsonPrimitive.content),
            HarnessJson.decodeFromJsonElement(Message.serializer(), o["message"]!!),
        )
    }
}

fun Psql.constitutions(): List<ConstitutionRow> =
    lines("select json_build_object('version', version, 'at', to_char(created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), 'document', document)::text from constitution_version order by version")
        .filter { it.isNotBlank() }.map { line ->
            val o = HarnessJson.parseToJsonElement(line).jsonObject
            ConstitutionRow(o["version"]!!.jsonPrimitive.content.toInt(), Instant.parse(o["at"]!!.jsonPrimitive.content), o["document"]!!)
        }

/** Pure: the constitution live at [at]: the highest version created by then. */
fun constitutionAt(rows: List<ConstitutionRow>, at: Instant): ConstitutionRow? = rows.filter { it.at <= at }.maxByOrNull { it.version }

fun Psql.mailAccounts(): List<MailAccount> =
    lines("select coalesce(manifest->'accounts', '[]'::jsonb)::text from connector where manifest->'handles' @> '\"send_email\"'::jsonb")
        .filter { it.isNotBlank() }
        .flatMap { HarnessJson.parseToJsonElement(it).jsonArray.map { a -> HarnessJson.decodeFromJsonElement(MailAccount.serializer(), a) } }

fun Psql.homeZone(): String =
    lines("select coalesce(doc->>'homeZone', '$DEFAULT_HOME_ZONE') from settings limit 1").firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME_ZONE

/** The scenario folders under [home], in number order. */
fun scenarioDirs(home: Path = LAB_HOME): List<Path> =
    (home.resolve("scenarios").takeIf { it.isDirectory() }?.listDirectoryEntries().orEmpty()).filter { it.isDirectory() }.sortedBy { it.fileName.toString() }

fun readScenario(dir: Path): ScenarioInput = LabJson.decodeFromString(ScenarioInput.serializer(), dir.resolve("input.json").readText())

/** The OpenRouter key from `OPENROUTER_API_KEY`, or the secret file (`OPENROUTER_API_KEY_FILE`, else the prod deploy's). Never printed. */
fun openRouterKey(): String {
    System.getenv("OPENROUTER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val file = Path(System.getenv("OPENROUTER_API_KEY_FILE") ?: (System.getProperty("user.home") + "/podman-compose/clarity-main/deploy/secrets/openrouter_api_key"))
    check(file.exists()) { "no OpenRouter key: set OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE" }
    return file.readText().trim()
}
