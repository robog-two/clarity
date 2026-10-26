package net.robog.clarity.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceTest : FunSpec({
    fun fixture(): Pair<Workspace, Path> {
        val base = Files.createTempDirectory("sbx")
        val ws = base.resolve("workspace").also(Files::createDirectories)
        val outside = base.resolve("outside").also(Files::createDirectories)
        Files.writeString(outside.resolve("secret.txt"), "top secret")
        return Workspace(ws) to outside
    }

    test("relative and absolute paths inside the workspace resolve") {
        val (ws, _) = fixture()
        ws.resolve("a/b.txt") shouldBe ws.root.resolve("a/b.txt")
        ws.resolve(ws.root.resolve("x").toString()) shouldBe ws.root.resolve("x")
        ws.resolve("a/../b") shouldBe ws.root.resolve("b")
        ws.resolve(".") shouldBe ws.root
    }

    test("dot-dot and absolute escapes are rejected with advice") {
        val (ws, outside) = fixture()
        shouldThrow<SandboxException> { ws.resolve("../outside/secret.txt") }.message shouldContain "outside /workspace"
        shouldThrow<SandboxException> { ws.resolve("a/../../x") }
        shouldThrow<SandboxException> { ws.resolve(outside.resolve("secret.txt").toString()) }
        shouldThrow<SandboxException> { ws.resolve("/etc/passwd") }
        shouldThrow<SandboxException> { ws.resolve("") }
    }

    test("symlinks pointing outside are rejected, for existing and not yet existing targets") {
        val (ws, outside) = fixture()
        Files.createSymbolicLink(ws.root.resolve("link"), outside)
        Files.createSymbolicLink(ws.root.resolve("filelink"), outside.resolve("secret.txt"))
        shouldThrow<SandboxException> { ws.resolve("link") }
        shouldThrow<SandboxException> { ws.resolve("link/secret.txt") }
        shouldThrow<SandboxException> { ws.resolve("link/new/file.txt") }
        shouldThrow<SandboxException> { ws.resolve("filelink") }
    }

    test("symlinks that stay inside are fine, dangling ones are rejected") {
        val (ws, _) = fixture()
        Files.createDirectories(ws.root.resolve("real"))
        Files.createSymbolicLink(ws.root.resolve("alias"), ws.root.resolve("real"))
        ws.resolve("alias/f.txt") shouldBe ws.root.resolve("real/f.txt")
        Files.createSymbolicLink(ws.root.resolve("dangling"), Path.of("/nonexistent/zzz"))
        shouldThrow<SandboxException> { ws.resolve("dangling") }
    }

    test("truncateMiddle keeps head and tail and reports the omission") {
        truncateMiddle("short", 100) shouldBe ("short" to false)
        val (t, cut) = truncateMiddle("a".repeat(100) + "b".repeat(100), 20)
        cut shouldBe true
        t shouldContain "180 characters omitted"
        t.startsWith("a".repeat(10)) shouldBe true
        t.endsWith("b".repeat(10)) shouldBe true
    }
})
