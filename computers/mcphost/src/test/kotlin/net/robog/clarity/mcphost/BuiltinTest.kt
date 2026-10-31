package net.robog.clarity.mcphost

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.shared.mcp.McpServerState

class BuiltinTest : FunSpec({
    val token = "1/777:kotlin-test-token-0123456789"
    fun asana(id: String = "asana001", extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = item(id) {
        put("label", "Asana"); put("kind", "builtin"); put("server", "asana"); extra()
    }
    fun withToken(name: String) = if (name == "secret") token else null
    fun problem(result: ItemResult) = result.spec.leftOrNull()

    test("a builtin item names its server; the token goes into the server's own variable and its host is always allowed") {
        val spec = parseItem(asana { put("args", strings("--evil")); put("env", strings("ASANA_WORKSPACE=42")) }, ::withToken).spec.getOrNull()!!
        spec.kind shouldBe ServerKind.BUILTIN
        spec.source shouldBe "builtin asana 1.0.0"
        spec.secrets shouldBe mapOf("ASANA_ACCESS_TOKEN" to token)
        spec.env shouldBe mapOf("ASANA_WORKSPACE" to "42")
        spec.args shouldBe emptyList()
        spec.allowHosts shouldContainExactly listOf("app.asana.com")
        spec.toString() shouldNotContain token
        parseItem(asana { put("allowHosts", strings("app.asana.com", "example.com")) }, ::withToken).spec.getOrNull()!!.allowHosts shouldContainExactly
            listOf("app.asana.com", "example.com")
        // the preset's secretEnv is accepted as long as it is the server's own
        parseItem(asana { put("secretEnv", "ASANA_ACCESS_TOKEN") }, ::withToken).spec.isRight() shouldBe true
    }

    test("builtin items are refused with clear words") {
        problem(parseItem(asana { put("server", "jira") }, ::withToken)) shouldBe "there is no built-in server 'jira' (there is: asana)"
        problem(parseItem(item("x1") { put("label", "A"); put("kind", "builtin") }, ::withToken)) shouldBe "server is not set (one of: asana)"
        problem(parseItem(asana(), { null })) shouldBe "secret is not set (it goes into ASANA_ACCESS_TOKEN)"
        problem(parseItem(asana { put("secretEnv", "TOKEN") }, ::withToken)) shouldBe "Asana takes its token in ASANA_ACCESS_TOKEN, not TOKEN"
        problem(parseItem(asana { put("env", strings("ASANA_ACCESS_TOKEN=x")) }, ::withToken)) shouldBe
            "ASANA_ACCESS_TOKEN goes in the token field, not in settings"
        problem(parseItem(asana { put("kind", "wat") }, ::withToken)) shouldBe "kind 'wat' is not one of npm, pypi, github-release, remote, builtin"
    }

    test("a new script version reinstalls and relists; the token changes only the run key") {
        val a = parseItem(asana(), ::withToken).spec.getOrNull()!!
        val b = parseItem(asana(), { if (it == "secret") "other" else null }).spec.getOrNull()!!
        a.installKey shouldBe b.installKey
        (a.runKey == b.runKey) shouldBe false
        (a.copy(version = "1.0.1").installKey == a.installKey) shouldBe false
    }

    test("the script's VERSION is the one the host knows") {
        val script = Builtin.dir.resolve("asana.py").readText()
        Regex("""(?m)^VERSION = "([^"]+)"""").find(script)!!.groupValues[1] shouldBe builtinServer("asana")!!.version
    }

    test("the schema shows a builtin item only its server, settings, token and hosts") {
        val props = hostConfigSchema["properties"]!!.jsonObject[SERVERS]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        fun visibleFor(kind: String) = props.filter { (_, p) ->
            val when_ = p.jsonObject["x-visible-when"]?.jsonObject?.get("kind")?.jsonArray?.map { it.jsonPrimitive.content }
            when_ == null || kind in when_
        }.keys
        visibleFor("builtin") shouldContainExactlyInAnyOrder listOf("id", "label", "description", "enabled", "kind", "server", "env", "secret", "allowHosts")
        visibleFor("remote") shouldContainExactlyInAnyOrder listOf("id", "label", "description", "enabled", "kind", "secret", "url", "allowHosts")
        ("server" in visibleFor("npm")) shouldBe false
        ("env" in visibleFor("npm")) shouldBe true
        props["server"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("asana")
        props["server"]!!.jsonObject["x-enum-titles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("Asana")
    }

    test("the Asana preset points at the builtin server, reaches only app.asana.com and tells where the token comes from") {
        val preset = serverPresets.map { it.jsonObject }.single { it["title"]!!.jsonPrimitive.content == "Asana" }
        val values = preset["values"]!!.jsonObject
        values["kind"]!!.jsonPrimitive.content shouldBe "builtin"
        values["server"]!!.jsonPrimitive.content shouldBe "asana"
        values["allowHosts"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("app.asana.com")
        val help = preset["description"]!!.jsonPrimitive.content
        help shouldContain "Manage developer apps"
        help shouldContain "Create new token"
        help shouldContain "whole account"
    }

    test("the install is the image's script, run by Python in isolated mode; a missing script fails clearly") {
        val spec = parseItem(asana(), ::withToken).spec.getOrNull()!!
        val home = ServerHome("asana001", Files.createTempDirectory("asana-home"), null)
        val runner = ProcessRunner(Isolation.SameUser, System.getenv("PATH"))
        val installer = RegistryInstaller(runner, EgressAccess(null, null), builtinDir = Builtin.dir)
        runBlocking { installer.install(spec, home) }.command shouldBe listOf("python3", "-I", "-B", Builtin.dir.resolve("asana.py").toString())
        val missing = RegistryInstaller(runner, EgressAccess(null, null), builtinDir = Path.of("/nonexistent"))
        shouldThrow<InstallFailed> { runBlocking { missing.install(spec, home) } }.message shouldContain "missing from this image"
    }

    test("a builtin server's environment: its proxy login and its token, nothing of the host's") {
        val spec = parseItem(asana { put("env", strings("ASANA_WORKSPACE=42")) }, ::withToken).spec.getOrNull()!!
        val home = ServerHome("asana001", Path.of("/data/servers/asana001"), 2001)
        val env = childEnv(home, "http://s-asana001:pw@egress-mcp:3128", spec.env + spec.secrets)
        env.keys shouldBe setOf(
            "PATH", "HOME", "TMPDIR", "LANG", "HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "NODE_USE_ENV_PROXY",
            "ASANA_WORKSPACE", "ASANA_ACCESS_TOKEN",
        )
        env["ASANA_ACCESS_TOKEN"] shouldBe token
        env["HTTPS_PROXY"] shouldBe "http://s-asana001:pw@egress-mcp:3128"
    }

    test("the supervisor installs, lists and runs the Asana server, which reaches a fake Asana only through its proxy login") {
        val certs = Files.createTempDirectory("asana-certs")
        val made = ProcessBuilder("python3", "-B", "-c", "import sys; sys.path.insert(0, sys.argv[1]); import fake_asana; fake_asana.make_certs(sys.argv[2])",
            Builtin.fakeAsana.parent.toString(), certs.toString()).redirectErrorStream(true).start()
        made.waitFor(60, TimeUnit.SECONDS) shouldBe true
        made.exitValue() shouldBe 0
        val fake = ProcessBuilder("python3", "-B", Builtin.fakeAsana.toString(), "--port", "0", "--certs", certs.toString(), "--token", token)
            .redirectErrorStream(true).start()
        try {
            val port = fake.inputStream.bufferedReader().readLine().substringAfterLast(':').toInt()
            Harness(proxyHost = "127.0.0.1", proxyPort = port).use { h ->
                val items = h.parse(asana { put("env", strings("SSL_CERT_FILE=${certs.resolve("ca.pem")}")) }, secrets = mapOf(("asana001" to "secret") to token))
                h.supervisor.reconcile(items)
                val listed = eventually(30.seconds) {
                    h.supervisor.servers().single().also { it.state shouldBe McpServerState.IDLE }
                }
                listed.tools.size shouldBe 12
                listed.source shouldBe "builtin asana 1.0.0"
                val reply = h.supervisor.handle("asana001", toolCall(7, "list_my_tasks") { put("due_within_days", 3) })
                    .shouldBeInstanceOf<McpOutcome.Reply>().body.jsonObject
                val text = reply.callText()
                text shouldContain "Essay outline · due"
                text shouldContain "gid 201"
                // the server's own run login, allowed only Asana
                Files.readString(h.egressDir.resolve("allow/s-asana001")).trim() shouldBe "app.asana.com"
                val wrong = h.supervisor.handle("asana001", toolCall(8, "get_task") { put("task_gid", "999") })
                    .shouldBeInstanceOf<McpOutcome.Reply>().body.jsonObject
                wrong.callText() shouldContain "not found in Asana (404)"
                (wrong["result"] as JsonObject)["isError"]!!.jsonPrimitive.content shouldBe "true"
            }
        } finally {
            fake.destroyForcibly()
        }
    }
})
