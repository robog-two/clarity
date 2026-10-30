package net.robog.clarity.mcphost

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.connector.sdk.ConfigItem

class PureTest : FunSpec({
    val t0 = Instant.parse("2026-10-30T08:00:00Z")

    test("backoff: 1, 2, 4 ... minutes, capped at 30") {
        (1..7).map { backoff(it).inWholeMinutes } shouldContainExactly listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L)
        backoff(0) shouldBe kotlin.time.Duration.ZERO
        backoff(1000).inWholeMinutes shouldBe 30L
    }

    test("eviction: room for three; then the least recently used idle one goes, busy ones last") {
        val views = listOf(RunningView("a", t0, 0), RunningView("b", t0 + 1.minutes, 0))
        evictionVictim(views, "c").shouldBeNull()
        val full = views + RunningView("c", t0 + 2.minutes, 0)
        evictionVictim(full, "d") shouldBe "a"
        evictionVictim(full.map { if (it.id == "a") it.copy(inFlight = 1) else it }, "d") shouldBe "b"
        evictionVictim(full.map { it.copy(inFlight = 1) }, "d") shouldBe "a"
        evictionVictim(full, "a").shouldBeNull() // already running: no one needs to go
    }

    test("idle: ten minutes without a request and nothing in flight") {
        val views = listOf(RunningView("a", t0, 0), RunningView("b", t0, 2), RunningView("c", t0 + 5.minutes, 0))
        idleServers(views, t0 + 10.minutes) shouldContainExactly listOf("a")
        idleServers(views, t0 + 9.minutes) shouldBe emptyList()
    }

    test("redaction removes known secrets and proxy passwords, and shortens") {
        val r = Redactor(listOf("ghp_secret_token", "abc"))
        r.clean("auth failed for ghp_secret_token at http://s-x:pAsS-w0rd@egress-mcp:3128") shouldBe
            "auth failed for ••• at http://s-x:•••@egress-mcp:3128"
        r.clean("abc stays (too short to redact)") shouldBe "abc stays (too short to redact)"
        r.clean("x".repeat(600)).length shouldBe 500
    }

    test("child env: nothing inherited, the proxy in every spelling, reserved names not overridable") {
        val home = ServerHome("s1", java.nio.file.Path.of("/data/servers/s1"), 20001)
        val env = childEnv(home, "http://s-s1:pw@egress-mcp:3128", mapOf("TOKEN" to "t", "HOME" to "/root"))
        env["HOME"] shouldBe "/data/servers/s1"
        env["TMPDIR"] shouldBe "/data/servers/s1/tmp"
        env["TOKEN"] shouldBe "t"
        env["NODE_USE_ENV_PROXY"] shouldBe "1"
        listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy").forEach { env[it] shouldBe "http://s-s1:pw@egress-mcp:3128" }
        env.keys shouldBe setOf("PATH", "HOME", "TMPDIR", "LANG", "HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "NODE_USE_ENV_PROXY", "TOKEN")
        childEnv(home, null, emptyMap()).keys shouldBe setOf("PATH", "HOME", "TMPDIR", "LANG")
    }

    test("per-uid argv: setpriv drops groups, capabilities and new privileges; no secret in it") {
        val home = ServerHome("s1", java.nio.file.Path.of("/data/servers/s1"), 20001)
        isolatedArgv(Isolation.PerUid(), home, listOf("/data/servers/s1/app/server", "stdio")) shouldContainExactly listOf(
            "/usr/bin/setpriv", "--reuid=20001", "--regid=20001", "--clear-groups", "--inh-caps=-all", "--no-new-privs", "--",
            "/data/servers/s1/app/server", "stdio",
        )
        isolatedArgv(Isolation.SameUser, home, listOf("x")) shouldContainExactly listOf("x")
    }

    test("uids are stable per server, never reused, and survive a restart") {
        val dir = Files.createTempDirectory("hoststate")
        val state = HostState(dir)
        state.uidFor("a") shouldBe 20000
        state.uidFor("b") shouldBe 20001
        state.uidFor("a") shouldBe 20000
        HostState(dir).uidFor("c") shouldBe 20002
        Files.getPosixFilePermissions(dir.resolve("host.json")) shouldBe PosixFilePermissions.fromString("rw-------")
    }

    test("proxy logins: allow-list and SHA-256 per user, 0640, password only in the returned credential") {
        val dir = Files.createTempDirectory("egress")
        val egress = EgressAccess(dir, "egress-mcp", 3128, readerGid = null).also { it.init() }
        val cred = egress.grant(runUser("s1"), listOf("api.github.com", ".example.org"))!!
        cred.url shouldBe "http://s-s1:${cred.password}@egress-mcp:3128"
        cred.toString() shouldNotContain cred.password
        dir.resolve("allow/s-s1").readText() shouldBe "api.github.com\n.example.org\n"
        dir.resolve("users/s-s1").readText().trim() shouldBe EgressAccess.sha256Hex(cred.password)
        dir.resolve("users/s-s1").readText() shouldNotContain cred.password
        Files.getPosixFilePermissions(dir.resolve("users/s-s1")) shouldBe PosixFilePermissions.fromString("rw-r-----")
        Files.getPosixFilePermissions(dir.resolve("users")) shouldBe PosixFilePermissions.fromString("rwxr-x---")
        val again = egress.grant(runUser("s1"), listOf("api.github.com"))!!
        (again.password == cred.password) shouldBe false
        egress.grant(installUser("s2"), installHosts(ServerKind.NPM))
        egress.retainOnly(setOf("i-s2"))
        dir.resolve("users/s-s1").exists() shouldBe false
        dir.resolve("allow/i-s2").readText() shouldBe "registry.npmjs.org\n"
        egress.revoke("i-s2")
        dir.resolve("allow/i-s2").exists() shouldBe false
        EgressAccess(null, null).grant("s-x", listOf("a")).shouldBeNull()
    }

    test("install hosts are only the kind's registry") {
        installHosts(ServerKind.NPM) shouldContainExactly listOf("registry.npmjs.org")
        installHosts(ServerKind.PYPI) shouldContainExactly listOf("pypi.org", "files.pythonhosted.org")
        installHosts(ServerKind.GITHUB_RELEASE) shouldContainExactly listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        installHosts(ServerKind.REMOTE) shouldBe emptyList()
    }

    test("command choice: configured, named like the package, or the only one") {
        pickCommand(null, "mcp-server-asana", listOf("mcp-server-asana")) shouldBe "mcp-server-asana"
        pickCommand("canvas-mcp-server", "canvas-mcp", listOf("canvas-mcp-server", "dotenv")) shouldBe "canvas-mcp-server"
        pickCommand(null, "x", listOf("only")) shouldBe "only"
        runCatching { pickCommand(null, "x", listOf("a", "b")) }.exceptionOrNull()!!.message shouldBe
            "the package has several commands (a, b); set command to one of them"
        runCatching { pickCommand("nope", "x", listOf("a")) }.exceptionOrNull()!!.message shouldBe "the package has no command 'nope' (it has: a)"
    }

    test("bearer comparison") {
        bearerMatches("Bearer k3y", "k3y") shouldBe true
        bearerMatches("Bearer k3y2", "k3y") shouldBe false
        bearerMatches("k3y", "k3y") shouldBe false
        bearerMatches(null, "k3y") shouldBe false
    }

    test("the schema lists the contract's properties in order, and every preset validates") {
        val servers = hostConfigSchema["properties"]!!.jsonObject[SERVERS]!!.jsonObject
        servers["x-item-title"]!!.jsonPrimitive.content shouldBe "label"
        servers["x-item-subtitle"]!!.jsonPrimitive.content shouldBe "package"
        servers["minItems"]!!.jsonPrimitive.content shouldBe "0"
        val props = servers["items"]!!.jsonObject["properties"]!!.jsonObject
        props.keys.toList() shouldContainExactly listOf(
            "id", "label", "description", "enabled", "kind", "package", "version", "asset", "sha256", "command", "args", "env",
            "secretEnv", "secret", "secretEnv2", "secret2", "url", "allowHosts",
        )
        props["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("npm", "pypi", "github-release", "remote")
        props["kind"]!!.jsonObject["x-enum-titles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
            listOf("npm package", "Python package", "GitHub release binary", "Remote URL")
        props["secret"]!!.jsonObject["x-secret"]!!.jsonPrimitive.content shouldBe "true"
        props["url"]!!.jsonObject["format"]!!.jsonPrimitive.content shouldBe "uri"
        val presets = servers["x-presets"]!!.jsonArray
        presets.map { it.jsonObject["title"]!!.jsonPrimitive.content } shouldContainExactly listOf("GitHub", "Canvas", "Asana")
        for (preset in presets) {
            val values = preset.jsonObject["values"]!!.jsonObject
            val result = parseItem(ConfigItem(SERVERS, "preset1", JsonObject(values + ("id" to JsonPrimitive("preset1")))), { if (it == "secret") "token-value" else null })
            result.spec.leftOrNull().shouldBeNull()
        }
    }

    test("SSE parsing finds the response with the id, skipping other events") {
        val stream = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/message","params":{}}

            data: {"jsonrpc":"2.0","id":7,
            data:  "result":{"ok":true}}
        """.trimIndent().plus("\n\n").byteInputStream()
        readSseResponse(stream, 7)!!["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content shouldBe "true"
    }
})
