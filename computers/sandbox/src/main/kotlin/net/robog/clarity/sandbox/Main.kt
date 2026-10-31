package net.robog.clarity.sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

/**
 * sandboxd: the agent's Linux workspace as an MCP server.
 *
 * SANDBOX_PORT (8940), SANDBOX_WORKSPACE (/workspace), SANDBOX_KEY_FILE (/run/secrets/sandbox_key),
 * SANDBOX_HTTP_PROXY (optional, e.g. http://egress-sandbox:3128: commands and fetch_page reach the public internet through it).
 * Without a key the server refuses to start unless SANDBOX_ALLOW_NO_AUTH=true.
 */
fun main() {
    val port = System.getenv("SANDBOX_PORT")?.toIntOrNull() ?: 8940
    val workspacePath = Path.of(System.getenv("SANDBOX_WORKSPACE") ?: "/workspace")
    val keyFile = System.getenv("SANDBOX_KEY_FILE") ?: "/run/secrets/sandbox_key"
    val key = runCatching { Files.readString(Path.of(keyFile)).trim() }.getOrNull()?.ifEmpty { null }
    if (key == null && System.getenv("SANDBOX_ALLOW_NO_AUTH") != "true") {
        log.error { "No bearer key in $keyFile. Create the sandbox_key secret (deploy/init-secrets.sh) or set SANDBOX_ALLOW_NO_AUTH=true for local development." }
        exitProcess(1)
    }
    Files.createDirectories(workspacePath)
    val workspace = Workspace(workspacePath)
    val proxy = System.getenv("SANDBOX_HTTP_PROXY")?.trim()?.ifEmpty { null }
    log.info { "sandboxd on :$port, workspace ${workspace.root}, auth ${if (key == null) "OFF" else "bearer key"}, internet ${proxy ?: "none"}" }
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        sandboxModule(sandboxTools(workspace, ShellRunner(workspace, proxy = proxy)), key)
    }.start(wait = true)
}
