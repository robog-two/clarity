package net.robog.clarity.sandbox

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ShellResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    /** Set when [output] is shortened; the full log under /workspace/.logs/. */
    val logFile: String?,
    val killedForOutputSize: Boolean,
    val durationMs: Long,
)

/** Keeps the first and last halves of [text] when it exceeds [cap] characters. Pure. */
fun truncateMiddle(text: String, cap: Int): Pair<String, Boolean> {
    if (text.length <= cap) return text to false
    val half = cap / 2
    val omitted = text.length - 2 * half
    return (text.take(half) + "\n[... $omitted characters omitted ...]\n" + text.takeLast(half)) to true
}

/**
 * Runs `bash -lc` commands inside the workspace. Each command gets its own session/process group
 * (killed as a whole on timeout or cancellation), a scrubbed environment, and at most
 * [maxConcurrent] run at a time. Output (stdout+stderr) is written straight to a log file under
 * `/workspace/.logs/`, from which the capped head+tail is returned.
 */
class ShellRunner(
    private val workspace: Workspace,
    private val maxConcurrent: Int = 4,
    private val outputCap: Int = 32 * 1024,
    private val maxLogBytes: Long = 64L * 1024 * 1024,
    private val shell: List<String> = listOf("bash", "-lc"),
    /** The egress proxy (http://host:port) commands reach the internet through; null for no internet. */
    val proxy: String? = null,
) {
    private val slots = Semaphore(maxConcurrent)
    private val counter = AtomicLong()
    private val setsid: String? = findOnPath("setsid")

    suspend fun run(command: String, cwd: Path, timeoutSeconds: Long): ShellResult {
        val acquired = withTimeoutOrNull(10_000) { slots.acquire(); true } ?: false
        if (!acquired) {
            throw SandboxException(
                "Too many commands are already running (limit $maxConcurrent). Wait for them to finish, or run fewer things in parallel (use `&` and `wait` inside one command).",
            )
        }
        try {
            return withContext(Dispatchers.IO) { execute(command, cwd, timeoutSeconds) }
        } finally {
            slots.release()
        }
    }

    private suspend fun execute(command: String, cwd: Path, timeoutSeconds: Long): ShellResult {
        Files.createDirectories(workspace.logsDir)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val log = workspace.logsDir.resolve("$stamp-${counter.incrementAndGet()}.log")
        val argv = buildList {
            setsid?.let { add(it) }
            addAll(shell)
            add(command)
        }
        val builder = ProcessBuilder(argv)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        builder.environment().apply {
            clear()
            put("PATH", "/usr/local/bin:/usr/bin:/bin")
            put("HOME", workspace.root.toString())
            put("LANG", "C.UTF-8")
            put("TERM", "dumb")
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PWD", cwd.toString())
            proxy?.let { putAll(proxyEnvironment(it)) }
        }
        val started = System.nanoTime()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw SandboxException("Could not start the shell (${e.message}). This is a sandbox fault, not a problem with your command.")
        }
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var timedOut = false
        var tooBig = false
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                if (runCatching { Files.size(log) }.getOrDefault(0L) > maxLogBytes) {
                    tooBig = true
                    break
                }
            }
        } finally {
            if (process.isAlive) {
                withContext(NonCancellable) { killGroup(process) }
            }
        }
        val exit = if (timedOut || tooBig) null else process.exitValue()
        // A normal exit can still leave background children holding the session; end them too.
        if (!timedOut && !tooBig) withContext(NonCancellable) { killGroupQuietly(process.pid()) }
        val (text, shortened) = readCapped(log)
        return ShellResult(
            exitCode = exit,
            timedOut = timedOut,
            output = text,
            logFile = if (shortened || tooBig) workspace.display(log) else null,
            killedForOutputSize = tooBig,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        ).also { if (!shortened && !tooBig) runCatching { Files.deleteIfExists(log) } }
    }

    private fun readCapped(log: Path): Pair<String, Boolean> {
        val size = Files.size(log)
        if (size <= outputCap) {
            return String(Files.readAllBytes(log), StandardCharsets.UTF_8) to false
        }
        val half = outputCap / 2
        RandomAccessFile(log.toFile(), "r").use { f ->
            val head = ByteArray(half).also { f.readFully(it) }
            f.seek(size - half)
            val tail = ByteArray(half).also { f.readFully(it) }
            val omitted = size - 2L * half
            return (String(head, StandardCharsets.UTF_8) +
                "\n[... $omitted bytes omitted ...]\n" +
                String(tail, StandardCharsets.UTF_8)) to true
        }
    }

    private fun killGroup(process: Process) {
        killGroupQuietly(process.pid())
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    /** With `setsid` the shell's pid is also its process-group id; `kill -- -pid` reaches the whole group. */
    private fun killGroupQuietly(pid: Long) {
        if (setsid == null) return
        try {
            ProcessBuilder("kill", "-KILL", "--", "-$pid")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        } catch (e: IOException) {
            // best effort
        } catch (e: CancellationException) {
            throw e
        }
    }
}

internal fun findOnPath(name: String): String? =
    (System.getenv("PATH") ?: "/usr/bin:/bin").split(':')
        .map { Path.of(it).resolve(name) }
        .firstOrNull { Files.isExecutable(it) }
        ?.toString()

/** Pure: the variables that send curl, wget, pip, git and friends through [proxy]; nothing bypasses it. */
fun proxyEnvironment(proxy: String): Map<String, String> = buildMap {
    for (name in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY")) put(name, proxy)
    put("no_proxy", "")
    put("NO_PROXY", "")
}
