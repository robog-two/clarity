package net.robog.clarity.mcphost

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import java.net.URI
import java.security.MessageDigest
import net.robog.clarity.connector.sdk.ConfigItem

/** How a server is obtained. [LOCAL] is not in the config schema: an operator switch for tests (see [HostSettings.localDir]). */
enum class ServerKind(val wire: String, val title: String) {
    NPM("npm", "npm package"),
    PYPI("pypi", "Python package"),
    GITHUB_RELEASE("github-release", "GitHub release binary"),
    REMOTE("remote", "Remote URL"),
    LOCAL("local", "Local command"),
    ;

    companion object {
        /** The kinds the app can choose, in schema order. */
        val offered = listOf(NPM, PYPI, GITHUB_RELEASE, REMOTE)

        fun parse(raw: String?): ServerKind? = entries.firstOrNull { it.wire == raw?.trim() }
    }
}

/**
 * One validated server of the config. [secrets] maps an environment variable to its opened value
 * (never logged: [toString] leaves values out); for [ServerKind.REMOTE], [bearer] is sent as
 * `Authorization: Bearer`.
 */
data class ServerSpec(
    val id: String,
    val label: String,
    val description: String = "",
    val enabled: Boolean = true,
    val kind: ServerKind,
    val pkg: String? = null,
    val version: String? = null,
    val asset: String? = null,
    val sha256: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    val url: String? = null,
    val bearer: String? = null,
    /** Hosts the server may reach, with the remote URL's host added. */
    val allowHosts: List<String> = emptyList(),
) {
    /** Tool prefix part, unique among servers. */
    val slug: String get() = slugOf(label)

    /** What was installed; a change reinstalls. */
    val installKey: String get() = digest(kind.wire, pkg.orEmpty(), version.orEmpty(), asset.orEmpty(), sha256.orEmpty(), command.orEmpty())

    /** Everything that changes how the server runs (secrets as a digest only); a change restarts it and lists it again. */
    val runKey: String get() = digest(
        installKey, args.joinToString("\u0000"), env.entries.joinToString("\u0000") { "${it.key}=${it.value}" },
        secrets.entries.joinToString("\u0000") { "${it.key}=${it.value}" }, url.orEmpty(), bearer.orEmpty(), allowHosts.joinToString(","),
    )

    /** Values that must never appear in a log line or an error. */
    val secretValues: List<String> get() = secrets.values + listOfNotNull(bearer)

    /** "npm @scope/pkg@1.2.3" and the like, for display. */
    val source: String get() = when (kind) {
        ServerKind.NPM -> "npm $pkg@$version"
        ServerKind.PYPI -> "pypi $pkg==$version"
        ServerKind.GITHUB_RELEASE -> "github $pkg@$version $asset"
        ServerKind.REMOTE -> "remote $url"
        ServerKind.LOCAL -> "local $command"
    }

    override fun toString(): String =
        "ServerSpec($label[$id], $source, args=$args, env=${env.keys}, secrets=${secrets.keys}, bearer=${if (bearer == null) "none" else "<redacted>"}, allowHosts=$allowHosts)"
}

/** A config item as the host sees it: usable, or why not (said in the person's words). */
data class ItemResult(val id: String, val label: String, val description: String, val spec: Either<String, ServerSpec>)

/** Lowercase, `[a-z0-9_]`, at most 24 characters: the `mcp_<slug>__` tool prefix. */
fun slugOf(label: String): String =
    label.lowercase().replace(Regex("[^a-z0-9_]+"), "_").replace(Regex("_+"), "_").trim('_').take(24).trimEnd('_')

private fun digest(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

/** Ids are app-generated (`[a-z0-9]`, 8 to 16); a little slack, nothing that could leave a path segment. */
val ID_PATTERN = Regex("^[a-z0-9]{1,32}$")

private val NPM_NAME = Regex("^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$")
private val PYPI_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
private val GITHUB_REPO = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$")
private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
private val PEP440 = Regex("^\\d+(\\.\\d+)*((a|b|rc)\\d+)?(\\.post\\d+)?(\\.dev\\d+)?$")
private val TAG = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}$")
private val ASSET = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}$")
private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
private val BIN_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}$")
private val REL_PATH = Regex("^[A-Za-z0-9._+-]+(/[A-Za-z0-9._+-]+)*$")
private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,99}$")
private val HOST = Regex("^\\.?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/** Set by the host for every server; a config cannot override them. */
val RESERVED_ENV = setOf(
    "PATH", "HOME", "TMPDIR", "USER", "LOGNAME",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy",
    "NODE_USE_ENV_PROXY",
)

const val MAX_ARGS = 50
const val MAX_ALLOW_HOSTS = 32

/**
 * Validates one config item. [openSecret] returns the plaintext of a sealed property (null when
 * unset) and may throw when it cannot be opened. [localDir] enables [ServerKind.LOCAL] commands under it.
 */
fun parseItem(item: ConfigItem, openSecret: (String) -> String?, localDir: String? = null): ItemResult {
    val label = item.text("label") ?: item.id
    val description = item.text("description").orEmpty()
    return ItemResult(item.id, label, description, either { validate(item, openSecret, localDir) })
}

private fun Raise<String>.validate(item: ConfigItem, openSecret: (String) -> String?, localDir: String?): ServerSpec {
    ensure(ID_PATTERN.matches(item.id)) { "the item id is not valid" }
    val label = ensureNotNull(item.text("label")) { "label is not set" }
    ensure(label.length <= 40) { "label is longer than 40 characters" }
    ensure(slugOf(label).isNotEmpty()) { "label needs at least one letter or digit" }
    val kind = ServerKind.parse(item.text("kind"))?.takeIf { it != ServerKind.LOCAL || localDir != null }
        ?: raise(if (item.text("kind") == null) "kind is not set" else "kind '${item.text("kind")}' is not one of npm, pypi, github-release, remote")
    val enabled = item.values["enabled"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: true

    fun secret(name: String): String? = try {
        openSecret(name)?.takeIf(String::isNotEmpty)
    } catch (e: Exception) {
        raise("$name could not be opened; enter it again")
    }

    val args = item.list("args")
    ensure(args.size <= MAX_ARGS) { "at most $MAX_ARGS args" }
    ensure(args.all { it.length <= 1000 && '\u0000' !in it }) { "an arg is too long" }
    val env = parseEnv(item.list("env"))
    val allow = item.list("allowHosts").map { it.lowercase().removeSuffix(".") }
    allow.firstOrNull { !HOST.matches(it) || it.trimStart('.').all { c -> c.isDigit() || c == '.' } }?.let { raise("allowHosts entry '$it' is not a host name") }
    ensure(allow.size <= MAX_ALLOW_HOSTS) { "at most $MAX_ALLOW_HOSTS allowHosts" }

    val base = ServerSpec(
        id = item.id, label = label, description = item.text("description").orEmpty(), enabled = enabled, kind = kind,
        args = args, env = env, allowHosts = allow.distinct(),
    )
    return when (kind) {
        ServerKind.REMOTE -> {
            val url = ensureNotNull(item.text("url")) { "url is not set" }
            val host = remoteHost(url)
            base.copy(url = url, bearer = secret("secret"), allowHosts = (base.allowHosts + host).distinct())
        }
        else -> {
            val secrets = buildMap {
                for ((envName, secretName) in listOf("secretEnv" to "secret", "secretEnv2" to "secret2")) {
                    val name = item.text(envName)
                    val value = secret(secretName)
                    when {
                        name == null && value == null -> Unit
                        name == null -> raise("$envName names the variable that receives $secretName; it is not set")
                        value == null -> raise("$secretName is not set (it goes into $name)")
                        !ENV_NAME.matches(name) -> raise("$envName '$name' is not a variable name")
                        name in RESERVED_ENV -> raise("$envName cannot be $name")
                        name in env || name in this -> raise("$name is set twice")
                        else -> put(name, value)
                    }
                }
            }
            installable(item, base.copy(secrets = secrets), localDir)
        }
    }
}

private fun Raise<String>.installable(item: ConfigItem, base: ServerSpec, localDir: String?): ServerSpec {
    val pkg = item.text("package")
    val version = item.text("version")
    val command = item.text("command")
    fun exactVersion(pattern: Regex): String {
        val v = ensureNotNull(version) { "version is not set" }
        ensure(v != "latest" && pattern.matches(v)) { "version must be exact, not $v" }
        return v
    }
    return when (base.kind) {
        ServerKind.NPM -> {
            val name = ensureNotNull(pkg) { "package is not set" }
            ensure(name.length <= 214 && NPM_NAME.matches(name)) { "package '$name' is not an npm package name" }
            command?.let { ensure(BIN_NAME.matches(it)) { "command '$it' must be the name of one of the package's commands" } }
            base.copy(pkg = name, version = exactVersion(SEMVER), command = command)
        }
        ServerKind.PYPI -> {
            val name = ensureNotNull(pkg) { "package is not set" }
            ensure(name.length <= 100 && PYPI_NAME.matches(name)) { "package '$name' is not a Python package name" }
            command?.let { ensure(BIN_NAME.matches(it)) { "command '$it' must be the name of one of the package's commands" } }
            base.copy(pkg = name, version = exactVersion(PEP440), command = command)
        }
        ServerKind.GITHUB_RELEASE -> {
            val repo = ensureNotNull(pkg) { "package is not set (owner/repo)" }
            ensure(GITHUB_REPO.matches(repo)) { "package '$repo' is not owner/repo" }
            val asset = ensureNotNull(item.text("asset")) { "asset is not set" }
            ensure(ASSET.matches(asset)) { "asset '$asset' is not a file name" }
            val sha = ensureNotNull(item.text("sha256")) { "sha256 is required for a release binary" }
            ensure(SHA256.matches(sha)) { "sha256 must be 64 hex characters" }
            command?.let { ensure(REL_PATH.matches(it) && it.split('/').none { s -> s == "." || s == ".." }) { "command '$it' must be a path inside the release asset" } }
            base.copy(pkg = repo, version = exactVersion(TAG), asset = asset, sha256 = sha.lowercase(), command = command)
        }
        ServerKind.LOCAL -> {
            val path = ensureNotNull(command) { "command is not set" }
            val dir = localDir!!.trimEnd('/')
            ensure(path.startsWith("$dir/") && REL_PATH.matches(path.removePrefix("$dir/")) && ".." !in path.split('/')) { "command must be under $dir" }
            base.copy(command = path)
        }
        ServerKind.REMOTE -> error("not installable")
    }
}

private fun Raise<String>.parseEnv(lines: List<String>): Map<String, String> = buildMap {
    for (line in lines) {
        val name = line.substringBefore('=', missingDelimiterValue = "")
        ensure(name.isNotEmpty()) { "env entry '${line.take(40)}' is not KEY=value" }
        ensure(ENV_NAME.matches(name)) { "env name '$name' is not a variable name" }
        ensure(name !in RESERVED_ENV) { "env cannot set $name" }
        ensure(name !in this) { "$name is set twice" }
        val value = line.substringAfter('=')
        ensure(value.length <= 4000 && '\u0000' !in value) { "env $name is too long" }
        put(name, value)
    }
}

/** The host of an https remote URL on the default port. */
private fun Raise<String>.remoteHost(url: String): String {
    val uri = try { URI(url) } catch (_: Exception) { raise("url is not a valid URL") }
    ensure(uri.scheme.equals("https", ignoreCase = true)) { "url must start with https://" }
    ensure(uri.rawUserInfo == null) { "url must not contain a user name or password; use secret" }
    ensure(uri.port == -1 || uri.port == 443) { "url must use port 443" }
    val host = ensureNotNull(uri.host?.lowercase()?.removeSuffix(".")) { "url has no host" }
    ensure(HOST.matches(host) && !host.startsWith(".")) { "url host '$host' is not a host name" }
    return host
}

/**
 * Validates every item of the config, then makes labels (and their tool prefixes) unique: a later
 * item that repeats an earlier label, ignoring case, is refused. One bad item never affects another.
 */
fun parseItems(items: List<ConfigItem>, openSecret: (ConfigItem, String) -> String?, localDir: String? = null): List<ItemResult> {
    val parsed = items.map { item -> parseItem(item, { openSecret(item, it) }, localDir) }
    val seenLabels = mutableSetOf<String>()
    val seenSlugs = mutableSetOf<String>()
    return parsed.map { result ->
        val spec = result.spec.getOrNull() ?: return@map result
        val clash = !seenLabels.add(spec.label.lowercase()) || !seenSlugs.add(spec.slug)
        if (clash) result.copy(spec = Either.Left("label '${spec.label}' is already used by another server")) else result
    }
}
