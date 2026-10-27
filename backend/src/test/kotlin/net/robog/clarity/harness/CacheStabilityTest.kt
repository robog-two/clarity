package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What keeps the lead's prompt cache prefix readable from one request to the next. */
class CacheStabilityTest : FunSpec({

    context("jsonb key order") {
        test("shorter keys first, equal lengths by bytes, recursively through objects and arrays") {
            val input = buildJsonObject {
                put("zeta", 1)
                put("ab", 2)
                put("b", 3)
                put("aa", 4)
                putJsonArray("list") { add(buildJsonObject { put("yy", 1); put("x", 2) }) }
                putJsonObject("nested") { put("long_name", 1); put("id", 2) }
            }
            val ordered = jsonbKeyOrder(input) as JsonObject
            ordered.keys.toList() shouldBe listOf("b", "aa", "ab", "list", "zeta", "nested")
            (ordered["list"] as JsonArray)[0].jsonObject.keys.toList() shouldBe listOf("x", "yy")
            ordered["nested"]!!.jsonObject.keys.toList() shouldBe listOf("id", "long_name")
            // the same content, so nothing the model said changes
            ordered shouldBe input
        }

        test("idempotent, and lengths count UTF-8 bytes like Postgres") {
            val input = buildJsonObject { put("é", 1); put("ab", 2); put("c", 3) }
            val once = jsonbKeyOrder(input)
            jsonbKeyOrder(once).toString() shouldBe once.toString()
            // "é" is two bytes (0xC3 0xA9): after "c", and after "ab" too since 0xC3 > 'a'
            (once as JsonObject).keys.toList() shouldBe listOf("c", "ab", "é")
        }

        test("journalStable reorders tool inputs and provider blocks, leaves the rest alone") {
            val tool = Block.ToolUse("t1", "send", buildJsonObject { put("text", "hi"); put("to", "x") })
            val raw = Block.Raw(buildJsonObject { put("type", "server_tool_use"); put("id", "s1"); putJsonObject("input") { put("query", "q"); put("n", 1) } })
            val stable = journalStable(listOf(Block.Text("t"), tool, raw))
            stable[0] shouldBe Block.Text("t")
            (stable[1] as Block.ToolUse).input.keys.toList() shouldBe listOf("to", "text")
            val json = (stable[2] as Block.Raw).json
            json.keys.toList() shouldBe listOf("id", "type", "input")
            json["input"]!!.jsonObject.keys.toList() shouldBe listOf("n", "query")
        }
    }

    context("message cache breakpoint") {
        val user = Message.user("hi")
        val call = Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "a", buildJsonObject { })))
        val results = Message(Role.USER, listOf(Block.Result("t1", "r1")))
        val done = Message(Role.ASSISTANT, listOf(Block.Text("done")))

        test("none on the first request of a thread") {
            messageCacheBreakpoint(listOf(user)) shouldBe null
        }
        test("inside a tool loop: on the user message the previous request ended with") {
            messageCacheBreakpoint(listOf(user, call, results)) shouldBe (0 to 0)
        }
        test("on a new turn: on the previous turn's last tool results, however much the new turn adds") {
            val many = Message(Role.USER, (1..30).map { Block.Text("attachment $it") })
            val messages = listOf(user, call, results, done, many, Message.system("[memory] notes"), Message.effort("low"))
            messageCacheBreakpoint(messages) shouldBe (2 to 0)
        }
        test("skips the system messages that ended the previous request") {
            val messages = listOf(user, Message.system("operator update"), Message.effort("low"), call, results)
            messageCacheBreakpoint(messages) shouldBe (0 to 0)
        }
        test("never before or on the latest compaction block (the provider drops what came before)") {
            val compacted = Message(Role.ASSISTANT, listOf(Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "s") })))
            messageCacheBreakpoint(listOf(user, call, results, compacted, Message.user("next"))) shouldBe null
            messageCacheBreakpoint(listOf(user, compacted, Message.user("next"), done, Message.user("again"))) shouldBe (2 to 0)
        }
        test("only on text and tool results") {
            val image = Message(Role.USER, listOf(Block.Raw(buildJsonObject { put("type", "image") })))
            messageCacheBreakpoint(listOf(image, done, Message.user("x"))) shouldBe null
        }
        test("four breakpoints at most: none left for the message when the system and automatic ones take them") {
            val request = ModelRequest(
                model = "anthropic/claude-opus-5.5",
                system = listOf(SystemBlock("static", cacheBreakpoint = true), SystemBlock("constitution", cacheBreakpoint = true)),
                messages = listOf(user, call, results, done, Message.user("again")),
                tools = listOf(ToolDefinition("a", "A", buildJsonObject { put("type", "object") })),
                maxTokens = 1000,
                effort = "medium",
                cache = CachePolicy(ttl = "1h"),
            )
            request.messageBreakpoint shouldBe (2 to 0)
            // three system breakpoints and the automatic one leave no room: no message breakpoint
            request.copy(system = request.system + SystemBlock("more", cacheBreakpoint = true)).messageBreakpoint shouldBe null
            request.copy(cache = null, system = listOf(SystemBlock("static"))).messageBreakpoint shouldBe null
        }
    }
})
