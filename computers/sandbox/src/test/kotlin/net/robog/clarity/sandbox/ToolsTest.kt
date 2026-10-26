package net.robog.clarity.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

class ToolsTest : FunSpec({
    fun newWorkspace() = Workspace(Files.createTempDirectory("sbx-ws"))
    fun tools(ws: Workspace, runner: ShellRunner = ShellRunner(ws)) = sandboxTools(ws, runner).associateBy { it.name }

    test("write, read with ranges, list") {
        val ws = newWorkspace()
        val t = tools(ws)
        t.getValue("write_file").invoke(args("path" to "notes/a.txt", "content" to "one\ntwo\nthree\nfour\n")) shouldContain "19 bytes"
        val all = t.getValue("read_file").invoke(args("path" to "notes/a.txt"))
        all shouldBe "1\tone\n2\ttwo\n3\tthree\n4\tfour\n"
        t.getValue("read_file").invoke(args("path" to "notes/a.txt", "start_line" to 2, "end_line" to 3)) shouldContain "2\ttwo\n3\tthree\n"
        t.getValue("list_files").invoke(args("max_depth" to 2)) shouldContain "notes/a.txt"
        shouldThrow<SandboxException> { t.getValue("read_file").invoke(args("path" to "missing.txt")) }.message shouldContain "list_files"
    }

    test("edit requires a prior read, a unique match and an unchanged file") {
        val ws = newWorkspace()
        val t = tools(ws)
        val edit = t.getValue("edit_file")
        Files.writeString(ws.root.resolve("f.txt"), "alpha beta\nbeta\n")

        shouldThrow<SandboxException> { edit.invoke(args("path" to "f.txt", "old_text" to "alpha", "new_text" to "x")) }.message shouldContain "not read"

        t.getValue("read_file").invoke(args("path" to "f.txt"))
        shouldThrow<SandboxException> { edit.invoke(args("path" to "f.txt", "old_text" to "beta", "new_text" to "x")) }.message shouldContain "2 times"
        shouldThrow<SandboxException> { edit.invoke(args("path" to "f.txt", "old_text" to "nope", "new_text" to "x")) }.message shouldContain "not found"
        edit.invoke(args("path" to "f.txt", "old_text" to "alpha", "new_text" to "ALPHA")) shouldContain "1 occurrence"
        Files.readString(ws.root.resolve("f.txt")) shouldBe "ALPHA beta\nbeta\n"

        // The file changes behind our back (e.g. via shell): the edit is refused until re-read.
        Files.writeString(ws.root.resolve("f.txt"), "changed\n")
        shouldThrow<SandboxException> { edit.invoke(args("path" to "f.txt", "old_text" to "changed", "new_text" to "x")) }.message shouldContain "changed since"
        t.getValue("read_file").invoke(args("path" to "f.txt"))
        edit.invoke(args("path" to "f.txt", "old_text" to "changed", "new_text" to "fixed"))
        Files.readString(ws.root.resolve("f.txt")) shouldBe "fixed\n"

        Files.writeString(ws.root.resolve("g.txt"), "x x x")
        t.getValue("read_file").invoke(args("path" to "g.txt"))
        edit.invoke(args("path" to "g.txt", "old_text" to "x", "new_text" to "y", "replace_all" to true)) shouldContain "3 occurrences"
    }

    test("file tools refuse paths outside the workspace") {
        val ws = newWorkspace()
        val t = tools(ws)
        for (name in listOf("read_file", "write_file", "edit_file")) {
            shouldThrow<SandboxException> {
                t.getValue(name).invoke(args("path" to "../escape.txt", "content" to "x", "old_text" to "a", "new_text" to "b"))
            }.message shouldContain "outside /workspace"
        }
        shouldThrow<SandboxException> { t.getValue("shell").invoke(args("command" to "true", "cwd" to "../..")) }
    }

    test("search_files finds matches and reports none").config(enabled = findOnPath("rg") != null) {
        val ws = newWorkspace()
        val t = tools(ws)
        Files.writeString(ws.root.resolve("a.md"), "hello world\nbye\n")
        Files.writeString(ws.root.resolve("b.txt"), "hello again\n")
        val found = t.getValue("search_files").invoke(args("pattern" to "hello"))
        found shouldContain "a.md:1:hello world"
        found shouldContain "b.txt:1:hello again"
        val globbed = t.getValue("search_files").invoke(args("pattern" to "hello", "glob" to "*.md"))
        globbed shouldContain "a.md"
        globbed shouldNotContain "b.txt"
        t.getValue("search_files").invoke(args("pattern" to "zzz")) shouldBe "No matches."
    }

    test("shell returns output and exit code, runs in cwd, scrubs the environment") {
        val ws = newWorkspace()
        Files.createDirectories(ws.root.resolve("sub"))
        val sh = tools(ws).getValue("shell")
        sh.invoke(args("command" to "echo hi; echo err >&2; exit 3")) shouldContain "hi\nerr\n[exit code 3"
        sh.invoke(args("command" to "pwd", "cwd" to "sub")) shouldContain ws.root.resolve("sub").toString()
        val env = sh.invoke(args("command" to "env"))
        env shouldContain "HOME=${ws.root}"
        env shouldNotContain "JAVA_HOME"
        env shouldNotContain "SANDBOX_"
    }

    test("with a proxy, commands get it for every tool and the description says the internet is reachable") {
        val ws = newWorkspace()
        val offline = tools(ws).getValue("shell")
        offline.description shouldContain "no internet"
        offline.invoke(args("command" to "env")) shouldNotContain "proxy"
        val online = tools(ws, ShellRunner(ws, proxy = "http://egress-sandbox:3128")).getValue("shell")
        online.description shouldContain "through a proxy"
        online.description shouldContain "/workspace/downloads"
        val env = online.invoke(args("command" to "env"))
        env shouldContain "https_proxy=http://egress-sandbox:3128"
        env shouldContain "HTTP_PROXY=http://egress-sandbox:3128"
        env shouldContain "no_proxy="
    }

    test("shell kills the whole process group on timeout") {
        val ws = newWorkspace()
        val sh = tools(ws).getValue("shell")
        val started = System.nanoTime()
        val out = sh.invoke(args("command" to "(sleep 30; touch survivor) & sleep 30", "timeout_seconds" to 1))
        out shouldContain "timed out after 1s"
        (((System.nanoTime() - started) / 1_000_000_000) < 20) shouldBe true // well under the 30 s sleeps
        Thread.sleep(300)
        Files.exists(ws.root.resolve("survivor")) shouldBe false
    }

    test("large output is cut to head and tail and the full log is kept under .logs") {
        val ws = newWorkspace()
        val sh = tools(ws).getValue("shell")
        val out = sh.invoke(args("command" to "seq 1 20000"))
        out.startsWith("1\n") shouldBe true
        out shouldContain "\n20000\n"
        out shouldContain "bytes omitted"
        out shouldContain "full log: /workspace/.logs/"
        val log = Files.list(ws.logsDir).use { it.toList().single() }
        Files.readString(log).lines().filter { it.isNotEmpty() }.size shouldBe 20000
        (out.length < 33 * 1024) shouldBe true
    }

    test("at most the configured number of commands run at once") {
        val ws = newWorkspace()
        val runner = ShellRunner(ws, maxConcurrent = 1)
        val sh = sandboxTools(ws, runner).first { it.name == "shell" }
        kotlinx.coroutines.runBlocking {
            val first = async { sh.invoke(args("command" to "sleep 1; echo done")) }
            kotlinx.coroutines.delay(300)
            // The slot is held; the second call waits for it and then runs (the wait is bounded at 10 s).
            sh.invoke(args("command" to "echo second")) shouldContain "second"
            first.await() shouldContain "done"
        }
    }
})
