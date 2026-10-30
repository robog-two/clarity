package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration as JDuration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

/** An install that did not work; [message] is short, redacted and meant for the person. */
class InstallFailed(message: String) : Exception(message)

/** Gets a server's code into its home and says how to start it. */
fun interface Installer {
    suspend fun install(spec: ServerSpec, home: ServerHome): Installed
}

/** Largest release asset accepted. */
const val MAX_ASSET_BYTES = 300L * 1024 * 1024

/**
 * Installs as the server's own uid (so nothing it installs can touch another server), reaching only
 * the kind's registry hosts through the egress proxy with a login that exists only for the install.
 *
 * - npm: `npm install --ignore-scripts` of the exact version into `<home>/pkg`; the command is one
 *   of the package's bins.
 * - pypi: a venv at `<home>/venv` made by `uv`, then `uv pip install --no-build` of `pkg==version`
 *   (wheels only, so no package code runs at install time).
 * - github-release: the host downloads the asset itself (through the proxy) and checks its SHA-256
 *   while streaming it into the home; only a matching file is kept and unpacked (tar.gz, zip or a
 *   bare binary).
 */
class RegistryInstaller(
    private val runner: ProcessRunner,
    private val egress: EgressAccess,
    private val path: String = SYSTEM_PATH,
) : Installer {
    override suspend fun install(spec: ServerSpec, home: ServerHome): Installed {
        val credential = egress.grant(installUser(spec.id), installHosts(spec.kind))
        val redactor = Redactor(listOfNotNull(credential?.password) + spec.secretValues)
        try {
            val env = childEnv(home, credential?.url, emptyMap(), path)
            return when (spec.kind) {
                ServerKind.NPM -> npm(spec, home, env, redactor)
                ServerKind.PYPI -> pypi(spec, home, env, redactor)
                ServerKind.GITHUB_RELEASE -> release(spec, home, credential, redactor)
                ServerKind.LOCAL -> Installed(spec.installKey, listOf(spec.command!!))
                ServerKind.REMOTE -> Installed(spec.installKey, emptyList())
            }
        } finally {
            egress.revoke(installUser(spec.id))
        }
    }

    private suspend fun step(home: ServerHome, what: String, command: List<String>, env: Map<String, String>, redactor: Redactor): String {
        val result = runner.run(home, command, env, timeout = 10.minutes)
        if (!result.ok) {
            val tail = result.output.lines().filter(String::isNotBlank).takeLast(3).joinToString(" / ")
            throw InstallFailed("$what failed: ${redactor.clean(tail, 300)}")
        }
        return result.output
    }

    private suspend fun npm(spec: ServerSpec, home: ServerHome, base: Map<String, String>, redactor: Redactor): Installed {
        val prefix = home.dir.resolve("pkg").toString()
        val env = base + mapOf(
            "npm_config_cache" to home.dir.resolve(".npm").toString(),
            "npm_config_registry" to "https://registry.npmjs.org/",
            "npm_config_update_notifier" to "false",
            "npm_config_fund" to "false",
            "npm_config_audit" to "false",
            "npm_config_ignore_scripts" to "true",
        ) + (base["HTTPS_PROXY"]?.let { mapOf("npm_config_https_proxy" to it, "npm_config_proxy" to it) } ?: emptyMap())
        step(home, "cleaning the old install", listOf("rm", "-rf", prefix), env, redactor)
        step(home, "npm install", listOf(
            "npm", "install", "--prefix", prefix, "--ignore-scripts", "--no-audit", "--no-fund", "--omit=dev",
            "--no-package-lock", "--save-exact", "${spec.pkg}@${spec.version}",
        ), env, redactor)
        val binDir = "$prefix/node_modules/.bin"
        val bins = step(home, "listing the package's commands", listOf("ls", "-1", binDir), env, redactor).lines().filter(String::isNotBlank)
        val bin = pickCommand(spec.command, spec.pkg!!.substringAfter('/'), bins)
        return Installed(spec.installKey, listOf("$binDir/$bin"))
    }

    private suspend fun pypi(spec: ServerSpec, home: ServerHome, base: Map<String, String>, redactor: Redactor): Installed {
        val venv = home.dir.resolve("venv").toString()
        val env = base + mapOf(
            "UV_CACHE_DIR" to home.dir.resolve(".cache/uv").toString(),
            "UV_PYTHON_DOWNLOADS" to "never",
            "UV_NO_PROGRESS" to "1",
            "UV_INDEX_URL" to "https://pypi.org/simple",
        )
        step(home, "cleaning the old install", listOf("rm", "-rf", venv), env, redactor)
        step(home, "creating the venv", listOf("uv", "venv", "--python", "/usr/bin/python3", venv), env, redactor)
        step(home, "pip install", listOf("uv", "pip", "install", "--python", "$venv/bin/python", "--no-build", "${spec.pkg}==${spec.version}"), env, redactor)
        val bins = step(home, "listing the package's commands", listOf("ls", "-1", "$venv/bin"), env, redactor).lines()
            .filter { it.isNotBlank() && it !in VENV_BINS && !it.startsWith("python") && !it.startsWith("activate") }
        val bin = pickCommand(spec.command, spec.pkg!!.lowercase(), bins)
        return Installed(spec.installKey, listOf("$venv/bin/$bin"))
    }

    private suspend fun release(spec: ServerSpec, home: ServerHome, credential: ProxyCredential?, redactor: Redactor): Installed {
        val app = home.dir.resolve("app").toString()
        val file = home.dir.resolve("download").resolve(spec.asset!!).toString()
        val env = childEnv(home, null, emptyMap(), path)
        step(home, "cleaning the old install", listOf("rm", "-rf", app, home.dir.resolve("download").toString()), env, redactor)
        step(home, "preparing", listOf("mkdir", "-p", "-m", "700", app, home.dir.resolve("download").toString()), env, redactor)

        val url = URI("https://github.com/${spec.pkg}/releases/download/${spec.version}/${spec.asset}")
        val client = proxiedHttpClient(credential, followRedirects = true)
        try {
            val response = client.sendAsync(
                HttpRequest.newBuilder(url).timeout(JDuration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            ).await()
            if (response.statusCode() != 200) {
                response.body().close()
                throw InstallFailed("downloading ${spec.asset} failed: HTTP ${response.statusCode()}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            // the file is written by the server's uid; the host hashes the bytes on the way in
            val written = runner.run(home, listOf("sh", "-c", "cat > \"\$1\"", "sh", file), env, stdin = { out ->
                withContext(Dispatchers.IO) {
                    response.body().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > MAX_ASSET_BYTES) throw InstallFailed("${spec.asset} is larger than ${MAX_ASSET_BYTES / 1024 / 1024} MB")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }, timeout = 10.minutes)
            if (!written.ok) throw InstallFailed("saving ${spec.asset} failed: ${redactor.clean(written.output, 200)}")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != spec.sha256) {
                step(home, "removing the download", listOf("rm", "-f", file), env, redactor)
                throw InstallFailed("${spec.asset} does not match its sha256 (got $actual)")
            }
        } catch (e: InstallFailed) {
            throw e
        } catch (e: java.io.IOException) {
            throw InstallFailed("downloading ${spec.asset} failed: ${redactor.clean(e.message ?: e.javaClass.simpleName, 200)}")
        } finally {
            client.close()
        }

        val asset = spec.asset
        val command = spec.command ?: spec.pkg!!.substringAfter('/')
        when {
            asset.endsWith(".tar.gz") || asset.endsWith(".tgz") ->
                step(home, "unpacking", listOf("tar", "-xzf", file, "-C", app, "--no-same-owner"), env, redactor)
            asset.endsWith(".zip") -> step(home, "unpacking", listOf("unzip", "-q", "-o", file, "-d", app), env, redactor)
            else -> step(home, "placing the binary", listOf("cp", file, "$app/$command"), env, redactor)
        }
        step(home, "removing the download", listOf("rm", "-rf", home.dir.resolve("download").toString()), env, redactor)
        val target = "$app/$command"
        step(home, "making $command executable", listOf("chmod", "0700", target), env, redactor)
        log.info { "installed ${spec.source} for ${spec.label}" }
        return Installed(spec.installKey, listOf(target))
    }

    companion object {
        private val VENV_BINS = setOf("pip", "pip3", "uv", "uvx", "deactivate", "pydoc")
    }
}

/** The command to run: the configured one if the package has it, else the one named like the package, else its only one. */
fun pickCommand(configured: String?, packageName: String, available: List<String>): String {
    if (configured != null) {
        if (configured in available) return configured
        throw InstallFailed("the package has no command '$configured' (it has: ${available.joinToString().ifEmpty { "none" }})")
    }
    return available.firstOrNull { it == packageName } ?: available.singleOrNull()
        ?: throw InstallFailed(
            if (available.isEmpty()) "the package has no command to run"
            else "the package has several commands (${available.joinToString()}); set command to one of them",
        )
}
