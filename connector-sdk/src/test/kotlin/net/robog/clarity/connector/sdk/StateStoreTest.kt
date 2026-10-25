package net.robog.clarity.connector.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class Cursor(val uid: Long, val note: String)

class StateStoreTest : FunSpec({
    test("roundtrips across instances (durable) and supports typed access, remove, keys") {
        runTest {
            val dir = Files.createTempDirectory("state-test")
            val a = FileStateStore(dir)
            a.putAs("cursor", Cursor(42, "x"))
            a.put("flag", JsonPrimitive(true))
            val b = FileStateStore(dir)
            b.getAs<Cursor>("cursor") shouldBe Cursor(42, "x")
            b.keys() shouldContainExactly setOf("cursor", "flag")
            b.remove("flag")
            FileStateStore(dir).get("flag") shouldBe null
            FileStateStore(dir).get("missing") shouldBe null
        }
    }

    test("writes are atomic: no temp files remain, and stale temp files do not affect loading") {
        runTest {
            val dir = Files.createTempDirectory("state-test")
            dir.resolve("state-crashed.tmp").writeText("{ torn json")
            val store = FileStateStore(dir)
            repeat(20) { store.put("n", JsonPrimitive(it)) }
            dir.listDirectoryEntries().map { it.fileName.toString() }.filter { it.endsWith(".tmp") && it != "state-crashed.tmp" } shouldBe emptyList()
            FileStateStore(dir).get("n") shouldBe JsonPrimitive(19)
        }
    }

    test("creates the directory on first write") {
        runTest {
            val dir = Files.createTempDirectory("state-test").resolve("nested/dir")
            FileStateStore(dir).put("k", JsonPrimitive("v"))
            FileStateStore(dir).get("k") shouldBe JsonPrimitive("v")
        }
    }
})
