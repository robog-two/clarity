package net.robog.clarity.sandbox

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A tool the sandbox serves over MCP. Failures are [SandboxException]s with advice for the model. */
interface SandboxTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val readOnly: Boolean get() = false

    suspend fun invoke(args: JsonObject): String
}

// ---- argument helpers ----------------------------------------------------------------------

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireString(key: String): String =
    string(key) ?: throw SandboxException("Missing required string argument \"$key\".")

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun schema(required: List<String> = emptyList(), props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties", props)
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.prop(name: String, type: String, description: String) =
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }

// ---- shell ---------------------------------------------------------------------------------

class ShellTool(private val workspace: Workspace, private val runner: ShellRunner) : SandboxTool {
    override val name = "shell"
    override val description = """
        Run a bash command in your Linux workspace (/workspace) and get its combined stdout+stderr and exit code.
        Available: coreutils, ripgrep (rg), jq, git, python3 (make a venv under /workspace to pip install), pandoc, pdftotext (poppler-utils), sqlite3, curl, wget.
        ${internetLine(runner.proxy != null)}
        Files the browser downloads appear in /workspace/downloads (read-only; newest first with `ls -t`; copy one elsewhere to change it).
        Files under /workspace persist between calls; nothing else does.
        Each call is a fresh shell (cd and exported variables do not carry over; pass `cwd` instead).
        Default timeout is $DEFAULT_TIMEOUT seconds (max $MAX_TIMEOUT); on timeout the whole process group is killed.
        Output over 32 KB is cut to its head and tail; the full output is saved under /workspace/.logs/ (path given in the result),
        so you can `rg` or `sed -n` it. Prefer redirecting big output to a file yourself.
    """.trimIndent()
    override val inputSchema = schema(listOf("command")) {
        prop("command", "string", "Bash script to run with `bash -lc`.")
        prop("cwd", "string", "Directory to run in, relative to /workspace (default: /workspace itself). Must exist.")
        prop("timeout_seconds", "integer", "Kill the command after this many seconds (default $DEFAULT_TIMEOUT, max $MAX_TIMEOUT).")
    }

    override suspend fun invoke(args: JsonObject): String {
        val command = args.requireString("command")
        val cwd = args.string("cwd")?.let(workspace::resolve) ?: workspace.root
        if (!cwd.isDirectory()) {
            throw SandboxException("cwd \"${args.string("cwd")}\" is not an existing directory. Create it first with `mkdir -p`, or omit cwd.")
        }
        val timeout = (args.long("timeout_seconds") ?: DEFAULT_TIMEOUT).coerceIn(1, MAX_TIMEOUT)
        val result = runner.run(command, cwd, timeout)
        return buildString {
            append(result.output)
            if (result.output.isNotEmpty() && !result.output.endsWith("\n")) append('\n')
            when {
                result.timedOut -> append("[timed out after ${timeout}s and killed; raise timeout_seconds (max $MAX_TIMEOUT) or run it in the background with output redirected to a file]\n")
                result.killedForOutputSize -> append("[killed: output exceeded the size limit; redirect it to a file or filter it]\n")
                else -> append("[exit code ${result.exitCode}, ${result.durationMs} ms]\n")
            }
            result.logFile?.let { append("[output was shortened; full log: /workspace/$it]\n") }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT = 60L
        const val MAX_TIMEOUT = 600L
    }
}

// ---- files ---------------------------------------------------------------------------------

/** What the model last saw of each file, so [EditFileTool] can refuse to edit stale content. */
class ReadTracker {
    private val seen = ConcurrentHashMap<Path, String>()
    fun record(path: Path, content: ByteArray) { seen[path] = digest(content) }
    fun lastSeen(path: Path): String? = seen[path]
    fun matches(path: Path, content: ByteArray): Boolean = seen[path] == digest(content)

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

const val MAX_FILE_BYTES = 10L * 1024 * 1024
private const val MAX_READ_CHARS = 64 * 1024

private fun readTextFile(workspace: Workspace, path: Path, original: String): ByteArray {
    if (!path.exists()) throw SandboxException("\"$original\" does not exist. Use list_files or search_files to find the right path.")
    if (path.isDirectory()) throw SandboxException("\"$original\" is a directory. Use list_files to see its contents.")
    if (!path.isRegularFile()) throw SandboxException("\"$original\" is not a regular file.")
    val size = Files.size(path)
    if (size > MAX_FILE_BYTES) {
        throw SandboxException("\"$original\" is ${size / 1024 / 1024} MB, too large to read whole. Use the shell (`head`, `sed -n`, `rg`) to look at part of it.")
    }
    return Files.readAllBytes(path)
}

private fun Path.exists() = Files.exists(this)

class ReadFileTool(private val workspace: Workspace, private val tracker: ReadTracker) : SandboxTool {
    override val name = "read_file"
    override val readOnly = true
    override val description = """
        Read a text file under /workspace with line numbers. Use start_line/end_line (1-based, inclusive) for a range;
        long files are cut at about 64 KB, and the result says which line to continue from.
        Read a file before edit_file: edits are refused if the file changed since you last read it.
    """.trimIndent()
    override val inputSchema = schema(listOf("path")) {
        prop("path", "string", "File path, relative to /workspace.")
        prop("start_line", "integer", "First line to return (default 1).")
        prop("end_line", "integer", "Last line to return (default: end of file, subject to the size cut).")
    }

    override suspend fun invoke(args: JsonObject): String = withContext(Dispatchers.IO) {
        val original = args.requireString("path")
        val path = workspace.resolve(original)
        val bytes = readTextFile(workspace, path, original)
        if (bytes.any { it == 0.toByte() }) {
            throw SandboxException("\"$original\" looks binary. Use the shell (`file`, `xxd | head`, `pdftotext`, `pandoc`) to inspect it.")
        }
        tracker.record(path, bytes)
        val lines = String(bytes, StandardCharsets.UTF_8).split("\n").let { if (it.last().isEmpty()) it.dropLast(1) else it }
        val start = (args.long("start_line") ?: 1).coerceAtLeast(1).toInt()
        val end = (args.long("end_line") ?: lines.size.toLong()).coerceAtMost(lines.size.toLong()).toInt()
        if (lines.isEmpty()) return@withContext "(empty file)"
        if (start > lines.size) throw SandboxException("start_line $start is past the end of the file (${lines.size} lines).")
        if (end < start) throw SandboxException("end_line $end is before start_line $start.")
        val out = StringBuilder()
        var last = start - 1
        for (n in start..end) {
            val line = "${n}\t${lines[n - 1]}\n"
            if (out.length + line.length > MAX_READ_CHARS && n > start) break
            out.append(line)
            last = n
        }
        if (last < end) out.append("[cut at line $last of ${lines.size}; continue with start_line=${last + 1}]\n")
        else if (end < lines.size) out.append("[${lines.size - end} more lines after line $end]\n")
        out.toString()
    }
}

class WriteFileTool(private val workspace: Workspace, private val tracker: ReadTracker) : SandboxTool {
    override val name = "write_file"
    override val description = """
        Create or completely overwrite a text file under /workspace (parent directories are created).
        For changing part of an existing file use edit_file instead. Returns the number of bytes written.
    """.trimIndent()
    override val inputSchema = schema(listOf("path", "content")) {
        prop("path", "string", "File path, relative to /workspace.")
        prop("content", "string", "Entire new contents of the file.")
    }

    override suspend fun invoke(args: JsonObject): String = withContext(Dispatchers.IO) {
        val original = args.requireString("path")
        val content = args.requireString("content").toByteArray(StandardCharsets.UTF_8)
        if (content.size > MAX_FILE_BYTES) throw SandboxException("Content is larger than ${MAX_FILE_BYTES / 1024 / 1024} MB; write it in pieces or generate it with the shell.")
        val path = workspace.resolve(original)
        if (path == workspace.root || path.isDirectory()) throw SandboxException("\"$original\" is a directory.")
        try {
            Files.createDirectories(path.parent)
            Files.write(path, content)
        } catch (e: IOException) {
            throw SandboxException("Could not write \"$original\": ${e.message}")
        }
        tracker.record(path, content)
        "Wrote ${content.size} bytes to ${workspace.display(path)}"
    }
}

class EditFileTool(private val workspace: Workspace, private val tracker: ReadTracker) : SandboxTool {
    override val name = "edit_file"
    override val description = """
        Replace exact text in an existing file. `old_text` must occur exactly once (include enough surrounding lines to make it unique),
        or set replace_all=true to replace every occurrence. The file must have been read (read_file) or written by you and not changed since;
        otherwise the edit is refused and you should read it again.
    """.trimIndent()
    override val inputSchema = schema(listOf("path", "old_text", "new_text")) {
        prop("path", "string", "File path, relative to /workspace.")
        prop("old_text", "string", "Exact text to find (whitespace and newlines included).")
        prop("new_text", "string", "Replacement text (may be empty to delete).")
        prop("replace_all", "boolean", "Replace every occurrence instead of requiring exactly one (default false).")
    }

    override suspend fun invoke(args: JsonObject): String = withContext(Dispatchers.IO) {
        val original = args.requireString("path")
        val old = args.requireString("old_text")
        val new = args.requireString("new_text")
        val all = args.bool("replace_all") ?: false
        if (old.isEmpty()) throw SandboxException("old_text is empty. To create a file use write_file.")
        if (old == new) throw SandboxException("old_text and new_text are identical; nothing to change.")
        val path = workspace.resolve(original)
        val bytes = readTextFile(workspace, path, original)
        if (tracker.lastSeen(path) == null) {
            throw SandboxException("You have not read \"$original\" yet. Call read_file on it first so the edit is based on its current contents.")
        }
        if (!tracker.matches(path, bytes)) {
            throw SandboxException("\"$original\" changed since you last read it (a shell command or another edit modified it). Call read_file again, then redo the edit.")
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        val count = countOccurrences(text, old)
        if (count == 0) throw SandboxException("old_text was not found in \"$original\". Check whitespace and indentation by re-reading the relevant lines with read_file.")
        if (count > 1 && !all) {
            throw SandboxException("old_text occurs $count times in \"$original\". Add surrounding context to make it unique, or pass replace_all=true.")
        }
        val updated = text.replace(old, new)
        val out = updated.toByteArray(StandardCharsets.UTF_8)
        Files.write(path, out)
        tracker.record(path, out)
        "Replaced $count occurrence${if (count == 1) "" else "s"} in ${workspace.display(path)}"
    }
}

internal fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var from = 0
    while (true) {
        val i = text.indexOf(needle, from)
        if (i < 0) return count
        count++
        from = i + needle.length
    }
}

class ListFilesTool(private val workspace: Workspace) : SandboxTool {
    override val name = "list_files"
    override val readOnly = true
    override val description = """
        List files and directories under a /workspace directory (default: /workspace), with sizes. Directories end in "/".
        max_depth 1 (default) lists just that directory; raise it to see a tree. Hidden entries are included except the .logs directory.
    """.trimIndent()
    override val inputSchema = schema {
        prop("path", "string", "Directory relative to /workspace (default \".\").")
        prop("max_depth", "integer", "How many levels to descend (default 1, max 6).")
    }

    override suspend fun invoke(args: JsonObject): String = withContext(Dispatchers.IO) {
        val original = args.string("path") ?: "."
        val dir = workspace.resolve(original)
        if (!dir.isDirectory()) throw SandboxException("\"$original\" is not a directory. Use read_file for files.")
        val depth = (args.long("max_depth") ?: 1).coerceIn(1, 6).toInt()
        val lines = mutableListOf<String>()
        var truncated = false
        Files.walk(dir, depth).use { stream ->
            val it = stream.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p == dir) continue
                if (p.startsWith(workspace.logsDir)) continue
                if (lines.size >= MAX_ENTRIES) { truncated = true; break }
                val isDir = Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val rel = workspace.display(p)
                lines += if (isDir) "$rel/" else "$rel  (${runCatching { Files.size(p) }.getOrDefault(0)} bytes)"
            }
        }
        lines.sort()
        if (lines.isEmpty()) "(empty)" else lines.joinToString("\n") + if (truncated) "\n[list cut at $MAX_ENTRIES entries; narrow `path` or use search_files]" else ""
    }

    private companion object { const val MAX_ENTRIES = 500 }
}

class SearchFilesTool(private val workspace: Workspace) : SandboxTool {
    override val name = "search_files"
    override val readOnly = true
    override val description = """
        Search file contents under /workspace with ripgrep (regex by default, case-sensitive unless ignore_case).
        Returns matching lines as `path:line:text`, at most max_results (default 100). Skips hidden files and .gitignored files.
        Use `glob` (e.g. "*.md") to restrict which files are searched.
    """.trimIndent()
    override val inputSchema = schema(listOf("pattern")) {
        prop("pattern", "string", "Regular expression to search for.")
        prop("path", "string", "File or directory relative to /workspace (default: everything).")
        prop("glob", "string", "Only search files matching this glob, e.g. \"*.py\".")
        prop("ignore_case", "boolean", "Case-insensitive search (default false).")
        prop("fixed_strings", "boolean", "Treat pattern as literal text, not a regex (default false).")
        prop("max_results", "integer", "Maximum number of matching lines (default 100, max 1000).")
    }

    override suspend fun invoke(args: JsonObject): String = withContext(Dispatchers.IO) {
        val pattern = args.requireString("pattern")
        val target = workspace.resolve(args.string("path") ?: ".")
        val max = (args.long("max_results") ?: 100).coerceIn(1, 1000)
        val cmd = mutableListOf("rg", "--line-number", "--no-heading", "--color=never", "--max-columns=300", "--max-count=$max")
        if (args.bool("ignore_case") == true) cmd += "-i"
        if (args.bool("fixed_strings") == true) cmd += "-F"
        args.string("glob")?.let { cmd += listOf("--glob", it) }
        cmd += listOf("-e", pattern, "--", target.toString())
        val process = try {
            ProcessBuilder(cmd).directory(workspace.root.toFile()).redirectErrorStream(false).start()
        } catch (e: IOException) {
            throw SandboxException("ripgrep (rg) is not available in this sandbox (${e.message}). Fall back to `grep -rn` via the shell tool.")
        }
        process.outputStream.close()
        val errors = StringBuilder()
        val errThread = Thread { errors.append(process.errorStream.readBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true; start() }
        val results = mutableListOf<String>()
        var capped = false
        process.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) {
                if (results.size >= max) { capped = true; break }
                results += line.removePrefix(workspace.root.toString() + "/")
            }
        }
        if (capped) process.destroyForcibly()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw SandboxException("Search took longer than 30 s. Narrow `path` or `glob`.")
        }
        errThread.join(1000)
        when {
            results.isNotEmpty() -> results.joinToString("\n") + if (capped) "\n[stopped at $max results; narrow the search]" else ""
            process.exitValue() == 1 -> "No matches."
            else -> throw SandboxException("Search failed: ${errors.toString().trim().ifEmpty { "rg exit ${process.exitValue()}" }}")
        }
    }
}

fun sandboxTools(workspace: Workspace, runner: ShellRunner = ShellRunner(workspace)): List<SandboxTool> {
    val tracker = ReadTracker()
    return listOf(
        ShellTool(workspace, runner),
        ReadFileTool(workspace, tracker),
        WriteFileTool(workspace, tracker),
        EditFileTool(workspace, tracker),
        SearchFilesTool(workspace),
        ListFilesTool(workspace),
    )
}

/** Pure: what the shell's description says about the internet. */
fun internetLine(online: Boolean): String =
    if (online) "Public websites are reachable through a proxy (curl, wget, pip, git over http(s); ports 80 and 443 only, never private or local addresses). Sites that need the person's login only work in the browser."
    else "There is no internet access."
