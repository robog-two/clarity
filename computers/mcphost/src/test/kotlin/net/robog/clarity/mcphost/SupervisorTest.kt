package net.robog.clarity.mcphost

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.shared.mcp.McpServerState
import org.slf4j.LoggerFactory

class SupervisorTest : FunSpec({
    suspend fun Harness.waitFor(id: String, state: McpServerState): HostServer =
        eventually(30.seconds) { supervisor.servers().first { it.id == id }.also { it.state shouldBe state } }

    suspend fun Harness.call(id: String, message: JsonObject): JsonObject =
        supervisor.handle(id, message).shouldBeInstanceOf<McpOutcome.Reply>().body.jsonObject

    suspend fun Harness.running(): List<String> = supervisor.servers().filter { it.state == McpServerState.RUNNING }.map { it.id }

    test("after install the host lists the tools once and stops the server; the listing is served without starting it") {
        Harness().use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("aaa111", "Fake")))
            val listed = h.waitFor("aaa111", McpServerState.IDLE)
            listed.tools.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldContainExactly
                listOf("echo", "pid", "env", "write_file", "read_file", "list_dir", "read_environ", "fetch", "direct", "whoami", "crash", "sleep")
            listed.tools[0].jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content shouldBe "true"
            listed.listedAt.shouldNotBeNull()
            listed.source shouldBe "local ${h.fakeCommand}"
            listed.allowHosts shouldContainExactly listOf("example.com")
            h.running() shouldBe emptyList()

            // initialize and tools/list come from the cached listing: still nothing running
            h.call("aaa111", request(1, "initialize", JsonObject(emptyMap())))["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!
                .jsonPrimitive.content shouldBe "fake"
            h.call("aaa111", request(2, "tools/list"))["result"]!!.jsonObject["tools"]!!.jsonArray shouldHaveSize 12
            h.running() shouldBe emptyList()

            // the first call starts it; the reply carries the client's own id
            val reply = h.call("aaa111", toolCall(42, "echo") { put("text", "hello") })
            reply["id"]!!.jsonPrimitive.content shouldBe "42"
            reply.callText() shouldBe "hello"
            h.running() shouldBe listOf("aaa111")
            (h.supervisor.handle("aaa111", JsonObject(mapOf("jsonrpc" to kotlinx.serialization.json.JsonPrimitive("2.0"), "method" to kotlinx.serialization.json.JsonPrimitive("notifications/initialized"))))) shouldBe McpOutcome.Accepted
        }
    }

    test("a paged tools/list is followed to the end") {
        Harness().use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("pag111", "Paged") { put("env", strings("FAKE_PAGED=1")) }))
            h.waitFor("pag111", McpServerState.IDLE).tools shouldHaveSize 12
        }
    }

    test("idle servers stop after ten minutes; a busy one does not") {
        val clock = TestClock()
        Harness(clock = clock).use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("idl111", "Idle"), h.fakeItem("bsy111", "Busy")))
            h.waitFor("idl111", McpServerState.IDLE); h.waitFor("bsy111", McpServerState.IDLE)
            h.call("idl111", toolCall(1, "echo") { put("text", "x") })
            h.call("bsy111", toolCall(1, "echo") { put("text", "x") })
            val slow = h.scope.async { h.call("bsy111", toolCall(2, "sleep") { put("seconds", 3) }) }
            delay(500)
            clock.advance(11.minutes)
            h.supervisor.stopIdle()
            h.running() shouldBe listOf("bsy111")
            slow.await().callText() shouldBe "slept"
            clock.advance(11.minutes)
            h.supervisor.stopIdle()
            h.running() shouldBe emptyList()
        }
    }

    test("at most three run at once: the least recently used one stops for a fourth") {
        val clock = TestClock()
        Harness(clock = clock).use { h ->
            val ids = listOf("lru001", "lru002", "lru003", "lru004")
            h.supervisor.reconcile(h.parse(*ids.mapIndexed { i, id -> h.fakeItem(id, "S$i") }.toTypedArray()))
            ids.forEach { h.waitFor(it, McpServerState.IDLE) }
            for (id in ids.take(3)) { h.call(id, toolCall(1, "echo") { put("text", id) }); clock.advance(1.minutes) }
            h.call("lru001", toolCall(2, "echo") { put("text", "again") }) // lru002 is now the oldest
            clock.advance(1.minutes)
            h.call("lru004", toolCall(1, "echo") { put("text", "fourth") })
            eventually(10.seconds) { h.running().toSet() shouldBe setOf("lru001", "lru003", "lru004") }
        }
    }

    test("a crash marks the server failed with a short error and backs off before the next start") {
        val clock = TestClock()
        Harness(clock = clock).use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("crs111", "Crashy")))
            h.waitFor("crs111", McpServerState.IDLE)
            val crashed = h.call("crs111", toolCall(1, "crash"))
            crashed["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldContain "Crashy stopped: exited with code 4"
            val failed = h.waitFor("crs111", McpServerState.FAILED)
            failed.error shouldBe "exited with code 4"
            val refused = h.supervisor.handle("crs111", toolCall(2, "echo") { put("text", "x") }).shouldBeInstanceOf<McpOutcome.Refused>()
            refused.status shouldBe 503
            refused.message shouldContain "Crashy is failing (exited with code 4); it is retried after 2026-10-30 08:01:00 UTC"
            clock.advance(61.seconds)
            h.call("crs111", toolCall(3, "echo") { put("text", "back") }).callText() shouldBe "back"
            h.waitFor("crs111", McpServerState.RUNNING)
            // the good call reset the count; two crashes in a row then wait two minutes
            clock.advance(1.minutes)
            h.call("crs111", toolCall(4, "crash"))
            h.waitFor("crs111", McpServerState.FAILED)
            clock.advance(61.seconds)
            h.call("crs111", toolCall(5, "crash"))
            h.waitFor("crs111", McpServerState.FAILED)
            clock.advance(61.seconds)
            h.supervisor.handle("crs111", toolCall(6, "echo") { put("text", "x") }).shouldBeInstanceOf<McpOutcome.Refused>()
                .message shouldContain "retried after 2026-10-30 08:05:02 UTC"
            clock.advance(60.seconds)
            h.call("crs111", toolCall(7, "echo") { put("text", "again") }).callText() shouldBe "again"
        }
    }

    test("a server that dies at start is failed after its install, and the other servers are untouched") {
        Harness().use { h ->
            val broken = h.fakeItem("brk111", "Broken") { put("env", strings("FAKE_CRASH_ON_START=1")) }
            val invalid = item("inv111") { put("label", "Canvas"); put("kind", "npm"); put("package", "x"); put("version", "^1.2") }
            h.supervisor.reconcile(h.parse(broken, invalid, h.fakeItem("oky111", "Fine")))
            h.waitFor("brk111", McpServerState.FAILED).error!! shouldContain "fake: crashing on purpose"
            h.waitFor("inv111", McpServerState.FAILED).error shouldBe "version must be exact, not ^1.2"
            h.waitFor("oky111", McpServerState.IDLE).tools shouldHaveSize 12
            h.call("oky111", toolCall(1, "echo") { put("text", "fine") }).callText() shouldBe "fine"
            val conflict = h.supervisor.handle("inv111", toolCall(1, "echo")).shouldBeInstanceOf<McpOutcome.Refused>()
            conflict.status shouldBe 409
            conflict.message shouldBe "Canvas: version must be exact, not ^1.2"
            val summary = h.supervisor.summary()
            summary.first shouldBe false
            summary.second shouldStartWith "Broken: exited with code 3"
            summary.second shouldContain "Canvas: version must be exact, not ^1.2"
            summary.second shouldContain "Fine: 12 tools"
        }
    }

    test("a server asking its client for something gets 'not supported' and the call still completes") {
        Harness().use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("ask111", "Asker") { put("env", strings("FAKE_ASK=1")) }))
            h.waitFor("ask111", McpServerState.IDLE)
            h.call("ask111", toolCall(1, "echo") { put("text", "ok") }).callText() shouldBe "ok"
        }
    }

    test("each server gets its own proxy login and allow-list; both go when it stops") {
        Harness().use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("prx111", "Proxied") { put("allowHosts", strings("api.github.com", "uploads.github.com")) }))
            h.waitFor("prx111", McpServerState.IDLE)
            h.egressDir.resolve("users/s-prx111").exists() shouldBe false // stopped after listing
            val proxy = h.call("prx111", toolCall(1, "env") { put("name", "HTTPS_PROXY") }).callText()
            proxy shouldStartWith "http://s-prx111:"
            proxy shouldContain "@egress-mcp:3128"
            val password = proxy.removePrefix("http://s-prx111:").substringBefore('@')
            h.egressDir.resolve("users/s-prx111").readText().trim() shouldBe EgressAccess.sha256Hex(password)
            h.egressDir.resolve("allow/s-prx111").readText() shouldBe "api.github.com\nuploads.github.com\n"
            h.call("prx111", toolCall(2, "env") { put("name", "NODE_USE_ENV_PROXY") }).callText() shouldBe "1"
            h.call("prx111", toolCall(3, "env") { put("name", "CLARITY_CONNECTOR_KEY") }).callText() shouldBe "<unset>"
            // the login of the install was only for the install
            h.egressDir.resolve("users/i-prx111").exists() shouldBe false
            h.supervisor.stopAll()
            h.egressDir.resolve("users/s-prx111").exists() shouldBe false
            h.egressDir.resolve("allow/s-prx111").exists() shouldBe false
        }
    }

    test("secrets reach only the server's environment: not its argv, not the logs, not errors") {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            Harness().use { h ->
                val token = "tok_" + "9f8e7d6c5b4a3210"
                val spec = h.fakeItem("sec111", "Secret") {
                    put("secretEnv", "SERVICE_TOKEN")
                    put("env", strings("FAKE_SAY=SERVICE_TOKEN"))
                    put("args", strings("--flag"))
                }
                h.supervisor.reconcile(h.parse(spec, secrets = mapOf(("sec111" to "secret") to token)))
                h.waitFor("sec111", McpServerState.IDLE)
                h.call("sec111", toolCall(1, "env") { put("name", "SERVICE_TOKEN") }).callText() shouldBe token
                val pid = h.call("sec111", toolCall(2, "pid")).callText()
                val cmdline = Files.readString(java.nio.file.Path.of("/proc/$pid/cmdline")).replace('\u0000', ' ')
                cmdline shouldContain "--flag"
                cmdline shouldNotContain token
                // the server printed the token to stderr; the log line has it redacted
                eventually(5.seconds) { appender.list.map { it.formattedMessage }.any { "fake: starting with SERVICE_TOKEN=•••" in it } shouldBe true }
                h.call("sec111", toolCall(3, "crash"))
                h.waitFor("sec111", McpServerState.FAILED).error!! shouldNotContain token
                appender.list.map { it.formattedMessage }.forEach { it shouldNotContain token }
                h.supervisor.servers().toString() shouldNotContain token
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    test("a changed token restarts the server and lists it again; a removed server is gone with its home and listing") {
        Harness().use { h ->
            fun spec() = h.fakeItem("chg111", "Changing") { put("secretEnv", "T") }
            h.supervisor.reconcile(h.parse(spec(), secrets = mapOf(("chg111" to "secret") to "first-token")))
            h.waitFor("chg111", McpServerState.IDLE)
            h.call("chg111", toolCall(1, "env") { put("name", "T") }).callText() shouldBe "first-token"
            h.supervisor.reconcile(h.parse(spec(), secrets = mapOf(("chg111" to "secret") to "second-token")))
            eventually(10.seconds) { h.call("chg111", toolCall(2, "env") { put("name", "T") }).callText() shouldBe "second-token" }

            val home = h.root.resolve("servers/chg111")
            home.exists() shouldBe true
            h.supervisor.reconcile(emptyList())
            h.supervisor.servers() shouldBe emptyList()
            eventually(10.seconds) { home.exists() shouldBe false }
            h.state.listing("chg111") shouldBe null
            h.supervisor.handle("chg111", toolCall(3, "echo")).shouldBeInstanceOf<McpOutcome.Refused>().status shouldBe 404
        }
    }

    test("a switched-off server is reported disabled and refuses calls; before the first config the host says it is starting") {
        Harness().use { h ->
            h.supervisor.handle("off111", toolCall(1, "echo")).shouldBeInstanceOf<McpOutcome.Refused>().status shouldBe 503
            h.supervisor.reconcile(h.parse(h.fakeItem("off111", "Off") { put("enabled", false) }))
            h.waitFor("off111", McpServerState.DISABLED)
            h.supervisor.handle("off111", toolCall(1, "echo")).shouldBeInstanceOf<McpOutcome.Refused>().message shouldBe "Off is switched off"
        }
    }

    test("after a restart the last known servers and listings are served before the first config arrives") {
        val h1 = Harness()
        h1.supervisor.reconcile(h1.parse(h1.fakeItem("rst111", "Restart")))
        h1.waitFor("rst111", McpServerState.IDLE)
        h1.close()
        val again = Supervisor(h1.state.let { HostState(h1.root.resolve("supervisor")) }, Homes(h1.root.resolve("servers"), h1.runner, h1.state),
            h1.runner, { _, _ -> error("no install") }, EgressAccess(null, null), h1.scope, childPath = System.getenv("PATH"))
        val listed = again.servers().single()
        listed.label shouldBe "Restart"
        listed.state shouldBe McpServerState.IDLE
        listed.tools shouldHaveSize 12
    }
})
