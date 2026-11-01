package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger {}

/**
 * How server processes are kept apart.
 *
 * [PerUid] is the production mode: the supervisor runs as root inside a rootless container (so
 * "root" is the deploying user on the host) with every capability dropped except four:
 *  - SETUID and SETGID, so `setpriv` can switch each child to its server's own uid and gid;
 *  - CHOWN, so the supervisor can hand each server its home directory (and the proxy files to the
 *    egress proxy's group);
 *  - KILL, so it can stop processes that now belong to another uid.
 * Children start through `setpriv --no-new-privs --clear-groups --inh-caps=-all`: a non-zero uid
 * loses every capability on exec, no supplementary groups, and nothing (no setuid binary) can give
 * them back. No DAC_OVERRIDE or FOWNER, so the supervisor itself cannot read a server's home either;
 * what it needs there it does by running a short command as that uid.
 *
 * [SameUser] runs children as the supervisor's own user: only for tests and local development.
 */
sealed interface Isolation {
    data class PerUid(val setpriv: String = "/bin/setpriv") : Isolation
    data object SameUser : Isolation
}

/** A server's home (`/data/servers/<id>`, mode 0700, owned by [uid]). */
data class ServerHome(val id: String, val dir: Path, val uid: Int?) {
    val tmp: Path get() = dir.resolve("tmp")
}

/** Output of a finished child: exit code and the first bytes of stdout and stderr together. */
data class RunResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

const val SYSTEM_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

/**
 * The whole environment of a server process: nothing from the supervisor's own environment, the
 * proxy (when there is one) in the spellings Node, Python, Go and curl read, then [extra]
 * (the item's env and opened secrets). [RESERVED_ENV] names cannot come from [extra].
 */
fun childEnv(home: ServerHome, proxyUrl: String?, extra: Map<String, String>, path: String = SYSTEM_PATH): Map<String, String> = buildMap {
    put("PATH", path)
    put("HOME", home.dir.toString())
    put("TMPDIR", home.tmp.toString())
    put("LANG", "C.UTF-8")
    if (proxyUrl != null) {
        for (name in listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy")) put(name, proxyUrl)
        // Node 24 sends fetch() and http(s) requests through HTTPS_PROXY only with this set.
        put("NODE_USE_ENV_PROXY", "1")
    }
    for ((k, v) in extra) if (k !in RESERVED_ENV) put(k, v)
}

/** argv that runs [command] as [home]'s uid under [isolation]. */
fun isolatedArgv(isolation: Isolation, home: ServerHome, command: List<String>): List<String> = when (isolation) {
    is Isolation.PerUid -> {
        val uid = requireNotNull(home.uid) { "no uid for ${home.id}" }
        // The supervisor cannot enter a home (0700, another uid), so the child changes into it after the switch.
        listOf(isolation.setpriv, "--reuid=$uid", "--regid=$uid", "--clear-groups", "--inh-caps=-all", "--no-new-privs", "--") +
            listOf("/bin/sh", "-c", CD_AND_EXEC, "mcphost-cd", home.dir.toString()) + command
    }
    Isolation.SameUser -> command
}

/** `sh -c` script: change into the first argument, then exec the rest. */
const val CD_AND_EXEC = "cd -- \"$1\" || exit 126; shift; exec \"$@\""

/** Where a child process starts: its home when the supervisor may enter it, else `/`. */
private fun startDir(isolation: Isolation, home: ServerHome): java.io.File =
    if (isolation is Isolation.PerUid) java.io.File("/") else home.dir.toFile()

/** Starts and runs child processes for servers, each as its own uid (see [Isolation]). */
class ProcessRunner(val isolation: Isolation, private val path: String = SYSTEM_PATH) {
    /** Starts [command] in [home] with exactly [env]. stdout and stderr are separate pipes. */
    fun spawn(home: ServerHome, command: List<String>, env: Map<String, String>): Process {
        val pb = ProcessBuilder(isolatedArgv(isolation, home, command)).directory(startDir(isolation, home))
        pb.environment().clear()
        pb.environment().putAll(env)
        return pb.start()
    }

    /**
     * Runs [command] to completion (killed after [timeout]); [stdin] is piped in when given. Output
     * is kept up to [outputLimit] bytes. Used for installs and small chores inside a home.
     */
    suspend fun run(
        home: ServerHome,
        command: List<String>,
        env: Map<String, String> = childEnv(home, null, emptyMap(), path),
        stdin: (suspend (java.io.OutputStream) -> Unit)? = null,
        timeout: Duration = 5.minutes,
        outputLimit: Int = 64 * 1024,
    ): RunResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(isolatedArgv(isolation, home, command)).directory(startDir(isolation, home)).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        val process = pb.start()
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { readBounded(process.inputStream, outputLimit) }
                if (stdin != null) process.outputStream.use { stdin(it) }
                val finished = withTimeoutOrNull(timeout) { runInterruptible { process.waitFor() } }
                if (finished == null) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                    return@coroutineScope RunResult(-1, out.await() + "\n(timed out after $timeout)")
                }
                RunResult(finished, out.await())
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Ends every process of [home]'s uid, including anything a server left running in the background. */
    suspend fun killAll(home: ServerHome, main: Process? = null) {
        main?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        when (isolation) {
            is Isolation.PerUid -> runCatching { run(home, listOf("kill", "-9", "-1"), timeout = 1.minutes) }
            Isolation.SameUser -> Unit
        }
        main?.let { withContext(Dispatchers.IO) { runInterruptible { it.waitFor(10, TimeUnit.SECONDS) } } }
    }
}

private fun readBounded(input: InputStream, limit: Int): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        val room = limit - out.size()
        if (room > 0) out.write(buf, 0, minOf(n, room))
    }
    return out.toString(Charsets.UTF_8)
}

/**
 * Server homes under [root] (`/data/servers`). Under [Isolation.PerUid] every server gets its own
 * uid from [uids], and its home is owned by it with mode 0700, so no other server (and not the
 * supervisor either, which has no DAC_OVERRIDE) can read it.
 */
class Homes(private val root: Path, private val runner: ProcessRunner, private val uids: UidAllocator) {
    fun home(id: String): ServerHome {
        require(ID_PATTERN.matches(id))
        return ServerHome(id, root.resolve(id), if (runner.isolation is Isolation.PerUid) uids.uidFor(id) else null)
    }

    /** Creates the home (0700, owned by the server's uid) and its tmp directory. */
    suspend fun prepare(id: String): ServerHome = withContext(Dispatchers.IO) {
        val home = home(id)
        Files.createDirectories(root)
        if (!home.dir.exists()) Files.createDirectory(home.dir)
        // Only a directory the supervisor still owns (new, or left by an interrupted start) is set up;
        // once it belongs to the server it is the server's (and without FOWNER the supervisor may not chmod it).
        val owner = Files.getAttribute(home.dir, "unix:uid") as Int
        if (home.uid == null || owner != home.uid) {
            Files.setPosixFilePermissions(home.dir, PosixFilePermissions.fromString("rwx------"))
            home.uid?.let { uid ->
                Files.setAttribute(home.dir, "unix:gid", uid)
                Files.setAttribute(home.dir, "unix:uid", uid)
            }
        }
        val made = runner.run(home, listOf("mkdir", "-p", "-m", "700", home.tmp.toString()), timeout = 1.minutes)
        check(made.ok) { "could not prepare the home of $id: ${made.output.take(200)}" }
        home
    }

    /** Deletes [id]'s home: its contents as its own uid, then the empty directory. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val home = home(id)
        if (!home.dir.exists()) return@withContext
        runner.killAll(home)
        val cleared = runner.run(home, listOf("find", home.dir.toString(), "-mindepth", "1", "-delete"), timeout = 5.minutes)
        if (!cleared.ok) log.warn { "could not clear the home of $id: ${cleared.output.take(200)}" }
        runCatching { Files.deleteIfExists(home.dir) }.onFailure { log.warn { "could not remove the home of $id: ${it.message}" } }
    }

    /** Ids that have a home on disk. */
    fun existing(): Set<String> = if (!root.exists()) emptySet() else
        Files.list(root).use { s -> s.map { it.fileName.toString() }.filter { ID_PATTERN.matches(it) }.toList().toSet() }
}

/** Stable uids for servers, never reused (a removed server's files could outlive it on a backup). */
interface UidAllocator {
    fun uidFor(id: String): Int
}
