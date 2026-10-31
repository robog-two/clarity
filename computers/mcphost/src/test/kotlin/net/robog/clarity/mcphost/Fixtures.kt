package net.robog.clarity.mcphost

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.connector.sdk.ConfigItem

/** A clock the test moves by hand. */
class TestClock(var now: Instant = Instant.parse("2026-10-30T08:00:00Z")) : Clock {
    override fun now() = now
    fun advance(by: Duration) { now += by }
}

/** The built-in servers' sources (`computers/mcphost/builtin`) and their test helpers. */
object Builtin {
    val dir: Path = Path.of(System.getProperty("mcphost.builtinDir") ?: error("mcphost.builtinDir is not set"))
    val fakeAsana: Path get() = dir.resolve("tests/fake_asana.py")
}

/** The fake stdio server, copied into a fresh "local" directory so kind `local` can run it. */
object Fake {
    val script: Path = Path.of(System.getProperty("mcphost.fakeServer") ?: error("mcphost.fakeServer is not set"))

    fun localDir(): Path {
        val dir = Files.createTempDirectory("mcphost-local")
        val target = dir.resolve("fake-mcp")
        Files.copy(script, target, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
        return dir
    }
}

fun item(id: String, values: JsonObject): ConfigItem = ConfigItem(SERVERS, id, JsonObject(values + ("id" to JsonPrimitive(id))))

fun item(id: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ConfigItem = item(id, buildJsonObject(build))

fun strings(vararg s: String) = JsonArray(s.map(::JsonPrimitive))

/** A supervisor over temp dirs, running servers as the test's own user. */
class Harness(
    val clock: TestClock = TestClock(),
    val policy: LifecyclePolicy = LifecyclePolicy(),
    val withEgress: Boolean = true,
    installer: Installer? = null,
    proxyHost: String = "egress-mcp",
    proxyPort: Int = 3128,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("mcphost-test")
    val local: Path = Fake.localDir()
    val runner = ProcessRunner(Isolation.SameUser, System.getenv("PATH"))
    val state = HostState(root.resolve("supervisor"))
    val egressDir: Path = root.resolve("egress")
    val egress = if (withEgress) EgressAccess(egressDir, proxyHost, proxyPort) else EgressAccess(null, null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supervisor = Supervisor(
        state = state,
        homes = Homes(root.resolve("servers"), runner, state),
        runner = runner,
        installer = installer ?: RegistryInstaller(runner, egress, System.getenv("PATH"), builtinDir = Builtin.dir),
        egress = egress,
        scope = scope,
        policy = policy,
        clock = clock,
        childPath = System.getenv("PATH"),
    ).also { it.init() }

    val fakeCommand: String get() = local.resolve("fake-mcp").toString()

    /** A valid local server item running the fake, with [extra] properties. */
    fun fakeItem(id: String, label: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = item(id) {
        put("label", label)
        put("kind", "local")
        put("command", fakeCommand)
        put("allowHosts", strings("example.com"))
        extra()
    }

    fun parse(vararg items: ConfigItem, secrets: Map<Pair<String, String>, String> = emptyMap()) =
        parseItems(items.toList(), { item, property -> secrets[item.id to property] }, local.toString())

    override fun close() {
        kotlinx.coroutines.runBlocking { supervisor.stopAll() }
        scope.cancel()
    }
}

fun request(id: Int, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0"); put("id", id); put("method", method)
    params?.let { put("params", it) }
}

fun toolCall(id: Int, name: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    request(id, "tools/call", buildJsonObject { put("name", name); putJsonObject("arguments", args) })

/** The text of a tools/call reply. */
fun JsonElement.callText(): String =
    jsonObject["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
