package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/** Registry hosts an install of each kind may reach, and only while it runs. */
fun installHosts(kind: ServerKind): List<String> = when (kind) {
    ServerKind.NPM -> listOf("registry.npmjs.org")
    ServerKind.PYPI -> listOf("pypi.org", "files.pythonhosted.org")
    // release downloads redirect to GitHub's asset storage
    ServerKind.GITHUB_RELEASE -> listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    ServerKind.REMOTE, ServerKind.LOCAL -> emptyList()
}

/** Proxy user of a running server and of its install. */
fun runUser(id: String) = "s-$id"
fun installUser(id: String) = "i-$id"

/** A proxy login. [toString] leaves the password out; [url] carries it and goes only into a child's environment. */
class ProxyCredential(val user: String, val password: String, val host: String, val port: Int) {
    val url: String get() = "http://$user:$password@$host:$port"
    override fun toString() = "ProxyCredential($user@$host:$port)"
}

/**
 * The egress proxy's view of the servers: per proxy user a password hash (`users/<user>`) and an
 * allow-list (`allow/<user>`, one host per line, a leading dot for subdomains), in a volume the
 * proxy mounts read-only. Squid's helpers read the files on every lookup, so a change takes effect
 * within the helpers' cache time without reloading Squid.
 *
 * Passwords are random per grant and stored only as SHA-256, so a stopped server's login is gone and
 * the files reveal nothing usable. The directories are 0750 and the files 0640, owned by root and the
 * proxy's group ([readerGid]); servers run as other uids with no groups and cannot read them.
 *
 * With [dir] or [proxyHost] null there is no proxy: [grant] returns null and servers have no way out.
 */
class EgressAccess(
    private val dir: Path?,
    private val proxyHost: String?,
    private val proxyPort: Int = 3128,
    private val readerGid: Int? = null,
) {
    private val random = SecureRandom()
    private val users get() = dir!!.resolve("users")
    private val allow get() = dir!!.resolve("allow")

    val enabled: Boolean get() = dir != null && proxyHost != null

    /** Creates the directories with their modes; run once at start. */
    fun init() {
        if (dir == null) return
        for (d in listOf(dir, users, allow)) {
            Files.createDirectories(d)
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwxr-x---"))
            readerGid?.let { Files.setAttribute(d, "unix:gid", it) }
        }
    }

    /** A fresh login for [user] that may reach exactly [hosts]; replaces any earlier one. */
    @Synchronized
    fun grant(user: String, hosts: List<String>): ProxyCredential? {
        if (!enabled) return null
        require(PROXY_USER.matches(user)) { "bad proxy user $user" }
        val bytes = ByteArray(32).also(random::nextBytes)
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        // the allow-list first, so the login never works before its list exists
        write(allow.resolve(user), hosts.joinToString("\n", postfix = "\n"))
        write(users.resolve(user), sha256Hex(password) + "\n")
        return ProxyCredential(user, password, proxyHost!!, proxyPort)
    }

    /** Replaces [user]'s allow-list, keeping its login. */
    @Synchronized
    fun updateHosts(user: String, hosts: List<String>) {
        if (!enabled || !users.resolve(user).exists()) return
        write(allow.resolve(user), hosts.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun revoke(user: String) {
        if (!enabled) return
        Files.deleteIfExists(users.resolve(user))
        Files.deleteIfExists(allow.resolve(user))
    }

    /** Removes every login not in [keep] (left over from before a restart). */
    @Synchronized
    fun retainOnly(keep: Set<String>) {
        if (!enabled) return
        for (d in listOf(users, allow)) {
            Files.list(d).use { s -> s.toList() }.filter { it.name !in keep }.forEach {
                Files.deleteIfExists(it)
                log.debug { "removed stale proxy file ${it.name}" }
            }
        }
    }

    private fun write(target: Path, text: String) {
        val tmp = Files.createTempFile(target.parent, ".tmp-", "")
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r-----"))
            readerGid?.let { Files.setAttribute(tmp, "unix:gid", it) }
            Files.writeString(tmp, text)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        /** What the proxy's helpers accept as a user name. */
        val PROXY_USER = Regex("^[a-z0-9-]{1,40}$")

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
