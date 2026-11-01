package net.robog.clarity.mcphost

import com.google.crypto.tink.hybrid.internal.TestSealer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.SealingKeys
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.Sealing

class ServerSpecTest : FunSpec({
    fun npm(version: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = item("abc12345") {
        put("label", "Canvas"); put("kind", "npm"); put("package", "@scope/canvas-mcp"); put("version", version); extra()
    }
    fun problem(result: ItemResult) = result.spec.leftOrNull()

    test("exact versions pass, ranges and latest are refused with the person's words") {
        parseItem(npm("1.2.3"), { null }).spec.isRight() shouldBe true
        parseItem(npm("1.2.3-beta.1"), { null }).spec.isRight() shouldBe true
        for (bad in listOf("^1.2", "~1.2.3", "latest", "1.x", "*", ">=1.0.0", "1.2")) {
            problem(parseItem(npm(bad), { null })) shouldBe "version must be exact, not $bad"
        }
        val py = item("p1") { put("label", "Py"); put("kind", "pypi"); put("package", "canvas-mcp"); put("version", "1.16.0") }
        parseItem(py, { null }).spec.getOrNull()!!.source shouldBe "pypi canvas-mcp==1.16.0"
        val pyRange = item("p2") { put("label", "Py"); put("kind", "pypi"); put("package", "canvas-mcp"); put("version", ">=1.0") }
        problem(parseItem(pyRange, { null })) shouldBe "version must be exact, not >=1.0"
    }

    test("a release needs an asset and a sha256; the command stays inside the asset") {
        fun rel(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = item("r1") {
            put("label", "GitHub"); put("kind", "github-release"); put("package", "github/github-mcp-server"); put("version", "v2.0.3"); extra()
        }
        problem(parseItem(rel { put("asset", "a.tar.gz") }, { null })) shouldBe "sha256 is required for a release binary"
        problem(parseItem(rel { put("asset", "a.tar.gz"); put("sha256", "abc") }, { null })) shouldBe "sha256 must be 64 hex characters"
        val sha = "f".repeat(64)
        problem(parseItem(rel { put("asset", "../a"); put("sha256", sha) }, { null }))!! shouldContain "is not a file name"
        problem(parseItem(rel { put("asset", "a.tgz"); put("sha256", sha); put("command", "../../bin/sh") }, { null }))!! shouldContain "inside the release asset"
        parseItem(rel { put("asset", "a.tgz"); put("sha256", sha.uppercase()); put("command", "bin/server") }, { null }).spec.getOrNull()!!.sha256 shouldBe sha
    }

    test("env entries must be KEY=value and cannot set the proxy, HOME or PATH") {
        problem(parseItem(npm("1.0.0") { put("env", buildJsonArray { add(JsonPrimitive("NOEQUALS")) }) }, { null }))!! shouldContain "not KEY=value"
        problem(parseItem(npm("1.0.0") { put("env", strings("HTTPS_PROXY=http://elsewhere:1")) }, { null })) shouldBe "env cannot set HTTPS_PROXY"
        problem(parseItem(npm("1.0.0") { put("env", strings("PATH=/tmp")) }, { null })) shouldBe "env cannot set PATH"
        parseItem(npm("1.0.0") { put("env", strings("A=1", "B=x=y")) }, { null }).spec.getOrNull()!!.env shouldBe mapOf("A" to "1", "B" to "x=y")
    }

    test("a secret needs its variable and the other way round; secrets never show in toString") {
        problem(parseItem(npm("1.0.0") { put("secretEnv", "TOKEN") }, { null })) shouldBe "secret is not set (it goes into TOKEN)"
        problem(parseItem(npm("1.0.0"), { if (it == "secret") "tok-value-1" else null }))!! shouldContain "secretEnv names the variable"
        problem(parseItem(npm("1.0.0") { put("secretEnv", "HOME") }, { if (it == "secret") "tok-value-1" else null })) shouldBe "secretEnv cannot be HOME"
        val spec = parseItem(npm("1.0.0") { put("secretEnv", "TOKEN") }, { if (it == "secret") "tok-value-1" else null }).spec.getOrNull()!!
        spec.secrets shouldBe mapOf("TOKEN" to "tok-value-1")
        spec.toString() shouldNotContain "tok-value-1"
        problem(parseItem(npm("1.0.0") { put("secretEnv", "TOKEN") }, { error("bad seal") })) shouldBe "secret could not be opened; enter it again"
    }

    test("remote URLs are https on 443 and their host joins allowHosts; the secret is the bearer") {
        fun remote(url: String) = item("rm1") { put("label", "Asana"); put("kind", "remote"); put("url", url) }
        problem(parseItem(remote("http://mcp.example.com/mcp"), { null })) shouldBe "url must start with https://"
        problem(parseItem(remote("https://mcp.example.com:8443/mcp"), { null })) shouldBe "url must use port 443"
        problem(parseItem(remote("https://user:pw@mcp.example.com/mcp"), { null }))!! shouldContain "must not contain a user name"
        val spec = parseItem(remote("https://MCP.example.com/mcp"), { if (it == "secret") "bearer-1" else null }).spec.getOrNull()!!
        spec.allowHosts shouldContainExactly listOf("mcp.example.com")
        spec.bearer shouldBe "bearer-1"
        spec.secretValues shouldBe listOf("bearer-1")
    }

    test("allowHosts are host names; IP addresses and wildcards are refused") {
        problem(parseItem(npm("1.0.0") { put("allowHosts", strings("10.0.0.1")) }, { null }))!! shouldContain "is not a host name"
        problem(parseItem(npm("1.0.0") { put("allowHosts", strings("*.example.com")) }, { null }))!! shouldContain "is not a host name"
        parseItem(npm("1.0.0") { put("allowHosts", strings("API.Example.com", ".example.org")) }, { null })
            .spec.getOrNull()!!.allowHosts shouldContainExactly listOf("api.example.com", ".example.org")
    }

    test("kind local exists only when the operator enables it, and only under its directory") {
        val local = item("l1") { put("label", "Fake"); put("kind", "local"); put("command", "/opt/local/fake") }
        problem(parseItem(local, { null }))!! shouldContain "is not one of npm, pypi, github-release, remote"
        parseItem(local, { null }, localDir = "/opt/local").spec.isRight() shouldBe true
        val outside = item("l2") { put("label", "Fake"); put("kind", "local"); put("command", "/opt/local/../bin/sh") }
        problem(parseItem(outside, { null }, localDir = "/opt/local")) shouldBe "command must be under /opt/local"
    }

    test("labels are unique ignoring case and by tool prefix; one bad item does not stop the others") {
        val items = listOf(
            item("a1") { put("label", "GitHub"); put("kind", "npm"); put("package", "x"); put("version", "1.0.0") },
            item("a2") { put("label", "github"); put("kind", "npm"); put("package", "y"); put("version", "1.0.0") },
            item("a3") { put("label", "Git Hub!"); put("kind", "npm"); put("package", "y"); put("version", "1.0.0") },
            item("a4") { put("label", "Broken"); put("kind", "npm"); put("package", "y"); put("version", "^1") },
            item("a5") { put("label", "Canvas"); put("kind", "npm"); put("package", "z"); put("version", "2.0.0") },
        )
        val results = parseItems(items, { _, _ -> null })
        results.map { it.spec.isRight() } shouldContainExactly listOf(true, false, true, false, true)
        problem(results[1]) shouldBe "label 'github' is already used by another server"
        results[2].spec.getOrNull()!!.slug shouldBe "git_hub"
    }

    test("slugs: lowercase, a-z0-9_, at most 24 characters") {
        slugOf("GitHub") shouldBe "github"
        slugOf("My Canvas (school)") shouldBe "my_canvas_school"
        slugOf("A very long server name that keeps going") shouldBe "a_very_long_server_name"
        slugOf("Ünïcode") shouldBe "n_code"
    }

    test("item secrets are opened with the item's sealing info; one sealed for another item does not open") {
        val keys = SealingKeys.generate()
        val runtime = ConnectorRuntime("mcphost", keys)
        val sealed = TestSealer.seal(keys.publicKey, "mcphost", Sealing.itemField(SERVERS, "k1", "secret"), "ghp_example")
        val sealedJson = JsonObject(ClarityJson.encodeToJsonElement(net.robog.clarity.shared.connector.SealedValue.serializer(), sealed).let { it as JsonObject } + ("type" to JsonPrimitive("sealed")))
        fun server(id: String) = buildJsonObject {
            put("id", id); put("label", "S$id"); put("kind", "npm"); put("package", "x"); put("version", "1.0.0")
            put("secretEnv", "TOKEN"); put("secret", sealedJson)
        }
        val config = ConnectorConfig(1, buildJsonObject { put(SERVERS, buildJsonArray { add(server("k1")); add(server("k2")) }) })
        val results = parseServers(config, runtime, null)
        results[0].spec.getOrNull()!!.secrets shouldBe mapOf("TOKEN" to "ghp_example")
        problem(results[1]) shouldBe "secret could not be opened; enter it again"
    }

    test("the install key changes with the version, the run key with a token") {
        val a = parseItem(npm("1.0.0"), { null }).spec.getOrNull()!!
        val b = parseItem(npm("1.0.1"), { null }).spec.getOrNull()!!
        val c = parseItem(npm("1.0.0") { put("secretEnv", "T") }, { if (it == "secret") "one" else null }).spec.getOrNull()!!
        val d = parseItem(npm("1.0.0") { put("secretEnv", "T") }, { if (it == "secret") "two" else null }).spec.getOrNull()!!
        (a.installKey == b.installKey) shouldBe false
        (c.installKey == d.installKey) shouldBe true
        (c.runKey == d.runKey) shouldBe false
        a.description shouldBe ""
    }
})
