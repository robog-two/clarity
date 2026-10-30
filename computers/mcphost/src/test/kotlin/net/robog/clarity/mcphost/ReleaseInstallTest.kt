package net.robog.clarity.mcphost

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.shared.mcp.McpServerState

class ReleaseInstallTest : FunSpec({
    // A release asset: a tar.gz holding the fake server, served the way GitHub does (a redirect to storage).
    val staging = Files.createTempDirectory("release")
    Files.copy(Fake.script, staging.resolve("fake-server"))
    staging.resolve("fake-server").toFile().setExecutable(true)
    val tgz = staging.resolve("fake_Linux_x86_64.tar.gz")
    ProcessBuilder("tar", "-czf", tgz.toString(), "-C", staging.toString(), "fake-server").inheritIO().start().waitFor() shouldBe 0
    val bytes = Files.readAllBytes(tgz)
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val server = embeddedServer(CIO, port = 0) {
        routing {
            get("/acme/fake/releases/download/v1.0.0/fake_Linux_x86_64.tar.gz") { call.respondRedirect("/storage/fake.tgz") }
            get("/storage/fake.tgz") { call.respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.OK) }
        }
    }.start(wait = false)
    val base = "http://127.0.0.1:${kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }}"
    afterSpec { server.stop(0, 0) }

    fun release(id: String, sha256: String) = item(id) {
        put("label", "Release $id"); put("kind", "github-release"); put("package", "acme/fake"); put("version", "v1.0.0")
        put("asset", "fake_Linux_x86_64.tar.gz"); put("sha256", sha256); put("command", "fake-server")
    }

    fun harness(): Harness {
        val runner = ProcessRunner(Isolation.SameUser, System.getenv("PATH"))
        return Harness(withEgress = false, installer = RegistryInstaller(runner, EgressAccess(null, null), System.getenv("PATH"), releaseBase = base))
    }

    test("a release whose sha256 matches is unpacked, listed and runs") {
        harness().use { h ->
            h.supervisor.reconcile(h.parse(release("rel111", sha)))
            val listed = eventually(30.seconds) { h.supervisor.servers().single().also { it.state shouldBe McpServerState.IDLE } }
            listed.source shouldBe "github acme/fake@v1.0.0 fake_Linux_x86_64.tar.gz"
            listed.tools.size shouldBe 12
            h.supervisor.handle("rel111", toolCall(1, "echo") { put("text", "from the release") })
                .let { (it as McpOutcome.Reply).body.jsonObject.callText() } shouldBe "from the release"
            h.root.resolve("servers/rel111/download").exists() shouldBe false
        }
    }

    test("a release whose sha256 does not match is not kept") {
        harness().use { h ->
            h.supervisor.reconcile(h.parse(release("bad111", "0".repeat(64))))
            val failed = eventually(30.seconds) { h.supervisor.servers().single().also { it.state shouldBe McpServerState.FAILED } }
            failed.error!! shouldStartWith "fake_Linux_x86_64.tar.gz does not match its sha256"
            failed.error shouldContain sha
            h.root.resolve("servers/bad111/download/fake_Linux_x86_64.tar.gz").exists() shouldBe false
            h.root.resolve("servers/bad111/app/fake-server").exists() shouldBe false
            h.state.current.installed["bad111"] shouldBe null
        }
    }
})
