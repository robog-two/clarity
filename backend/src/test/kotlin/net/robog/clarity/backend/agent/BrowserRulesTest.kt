package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

private fun click(role: String?, name: String?, url: String? = "https://shop.example.com/products/1"): StepVerdict =
    stepVerdict("click_by_uid", obj("uid" to "e1"), listOf(Element(role, name)), url)

class BrowserRulesTest : FunSpec({
    context("tool classification") {
        test("raw-script, privileged, preference, extension, debugging and recording tools are hidden") {
            listOf(
                "evaluate_script", "evaluate_privileged_script", "send_bidi_command", "set_firefox_prefs", "get_firefox_prefs",
                "install_extension", "uninstall_extension", "list_privileged_contexts", "select_privileged_context", "set_logpoint",
                "enable_debugger", "get_script_source", "screencast_start", "profiler_start", "restart_firefox", "close_firefox_session",
                "something_new_in_a_later_version",
            ).forEach { classifyBrowserTool(it) shouldBe BrowserKind.HIDDEN }
        }

        test("acting, navigating, capturing and reading tools") {
            listOf("click_by_uid", "fill_by_uid", "fill_form_by_uid", "type_text", "press_key", "upload_file_by_uid", "drag_by_uid_to_uid")
                .forEach { classifyBrowserTool(it) shouldBe BrowserKind.ACT }
            listOf("navigate_page", "new_page", "navigate_history").forEach { classifyBrowserTool(it) shouldBe BrowserKind.NAVIGATE }
            listOf("screenshot_page", "screenshot_by_uid", "print_page").forEach { classifyBrowserTool(it) shouldBe BrowserKind.CAPTURE }
            listOf("take_snapshot", "get_page_text", "list_pages", "select_page", "hover_by_uid", "list_console_messages")
                .forEach { classifyBrowserTool(it) shouldBe BrowserKind.READ }
        }
    }

    context("snapshot and page parsing") {
        test("reads uid, role and name from snapshot lines") {
            val parsed = parseSnapshot(
                """
                uid=1_0 document "Checkout"
                  uid=1_4 button "Place order" tag=button
                  uid=1_5 textbox "Card number" value=""
                  uid=1_6 link
                  uid=1_7 button "Say \"hi\""
                """.trimIndent(),
            )
            parsed["1_4"] shouldBe Element("button", "Place order")
            parsed["1_5"] shouldBe Element("textbox", "Card number")
            parsed["1_6"] shouldBe Element("link", null)
            parsed["1_7"] shouldBe Element("button", "Say \"hi\"")
        }

        test("reads where a link goes from the browser's snapshot lines") {
            val parsed = parseSnapshot(
                """
                uid=e260 a "Book an appointment" href="https://beautyservices.ulta..." text="Book an appointment"
                uid=e261 a "Top" href="#top"
                uid=e336 button "I Understand" text="I Understand"
                """.trimIndent(),
            )
            parsed["e260"] shouldBe Element("a", "Book an appointment", "https://beautyservices.ulta...")
            parsed["e261"]?.href shouldBe "#top"
            parsed["e336"] shouldBe Element("button", "I Understand")
            parsed.getValue("e260").detail("e260") shouldBe "a \"Book an appointment\" linking to https://beautyservices.ulta..."
        }

        test("reads the selected tab's url from list_pages") {
            parseSelectedUrl("2 pages (selected: 1)\n [0] Home (https://a.com/)\n>[1] Pay (now) (https://shop.example.com/checkout?x=(1))") shouldBe
                "https://shop.example.com/checkout?x=(1)"
            parseSelectedUrl("No pages") shouldBe null
            parseSelectedUrl("1 pages (selected: 0)\n>[0] Untitled (URL unavailable)") shouldBe null
        }

        test("targets and typed strings") {
            targetUids(buildJsonObject { put("uid", "a"); put("fromUid", "b"); put("toUid", "c") }) shouldBe listOf("a", "b", "c")
            typedStrings(obj("text" to "hello", "value" to "world")) shouldBe listOf("world", "hello")
        }
    }

    context("payment page detection") {
        test("by path, subdomain and host") {
            isPaymentPage("https://shop.example.com/checkout") shouldBe true
            isPaymentPage("https://shop.example.com/cart/review") shouldBe true
            isPaymentPage("https://shop.example.com/order?id=1") shouldBe true
            isPaymentPage("https://checkout.example.com/") shouldBe true
            isPaymentPage("https://www.paypal.com/signin") shouldBe true
            isPaymentPage("https://shop.example.com/products/1") shouldBe false
            isPaymentPage("https://shop.example.com/orders-are-great-blog") shouldBe false
            isPaymentPage(null) shouldBe false
        }
    }

    context("click rules") {
        test("buttons that send, submit, buy, book, delete, confirm, accept or sign up ask, in several phrasings") {
            listOf(
                "Send", "Send message", "Submit", "Submit application", "Pay now", "Buy now", "Place your order", "Book now", "Reserve a table",
                "Delete", "Delete account", "Confirm", "Confirm order", "Accept all", "I agree", "Sign up", "Sign-up for free", "Unsubscribe",
                "Subscribe", "Donate", "Transfer funds", "Cancel my subscription", "Register", "Publish",
            ).forEach { name ->
                withClue(name) { click("button", name).shouldBeInstanceOf<StepVerdict.Ask>() }
            }
        }

        test("money phrases are never alone; other asks are not") {
            (click("button", "Pay now") as StepVerdict.Ask).neverAlone shouldBe true
            (click("button", "Place order") as StepVerdict.Ask).neverAlone shouldBe true
            (click("button", "Delete") as StepVerdict.Ask).neverAlone shouldBe false
            (click("button", "Send") as StepVerdict.Ask).neverAlone shouldBe false
        }

        test("links and tabs navigate freely, even with words like order in them") {
            click("link", "Order history") shouldBe StepVerdict.Pass
            click("link", "Send feedback") shouldBe StepVerdict.Pass
            click("tab", "Reviews") shouldBe StepVerdict.Pass
            click("link", "Delete my account").shouldBeInstanceOf<StepVerdict.Ask>()
            click("link", "Checkout").shouldBeInstanceOf<StepVerdict.Ask>()
        }

        test("an <a> link that opens a booking or shop page is browsing; only the final step commits") {
            fun link(name: String, href: String?, url: String = "https://www.ulta.com/beautyservices/hair-salon") =
                stepVerdict("click_by_uid", obj("uid" to "e1"), listOf(Element("a", name, href)), url)
            link("Book an appointment", "https://beautyservices.ulta...") shouldBe StepVerdict.Pass
            link("Book an appointment", null) shouldBe StepVerdict.Pass
            link("Book now", "https://beautyservices.ulta.com/") shouldBe StepVerdict.Triage
            link("Unsubscribe", "https://news.example.com/u?id=1").shouldBeInstanceOf<StepVerdict.Ask>() // acts on a plain GET
            link("Buy now", "https://shop.example.com/p/1").shouldBeInstanceOf<StepVerdict.Ask>()
            link("Book now", "#").shouldBeInstanceOf<StepVerdict.Ask>()
            link("Show times", "javascript:void(0)") shouldBe StepVerdict.Triage
            link("Book now", "https://shop.example.com/x", "https://shop.example.com/checkout").shouldBeInstanceOf<StepVerdict.Ask>()
        }

        test("an unremarkable button goes to the triage") {
            click("button", "Next") shouldBe StepVerdict.Triage
            click("button", "Show more") shouldBe StepVerdict.Triage
            click(null, null) shouldBe StepVerdict.Triage
        }

        test("on a payment page the step buttons ask as payments") {
            val v = click("button", "Continue", "https://shop.example.com/checkout")
            v.shouldBeInstanceOf<StepVerdict.Ask>().neverAlone shouldBe true
            click("button", "Complete", "https://shop.example.com/checkout/step2").shouldBeInstanceOf<StepVerdict.Ask>()
            click("link", "Back to cart", "https://shop.example.com/checkout") shouldBe StepVerdict.Pass
        }
    }

    context("typing and key rules") {
        test("ordinary text fields pass") {
            stepVerdict("fill_by_uid", obj("uid" to "e1", "value" to "hello"), listOf(Element("textbox", "Search")), "https://a.com/") shouldBe StepVerdict.Pass
            stepVerdict("type_text", obj("text" to "hello"), emptyList(), "https://a.com/") shouldBe StepVerdict.Pass
            stepVerdict("fill_by_uid", obj("uid" to "e1", "value" to "hello"), listOf(Element("combobox", "Country")), "https://a.com/") shouldBe StepVerdict.Pass
        }

        test("typing a literal into a card field asks as a payment; a placeholder does not") {
            val field = listOf(Element("textbox", "Card number"))
            stepVerdict("fill_by_uid", obj("uid" to "e1", "value" to "4111111111111111"), field, "https://a.com/")
                .shouldBeInstanceOf<StepVerdict.Ask>().neverAlone shouldBe true
            stepVerdict("fill_by_uid", obj("uid" to "e1", "value" to "[secret:card_number]"), field, "https://shop.example.com/checkout") shouldBe StepVerdict.Pass
            stepVerdict("fill_by_uid", obj("uid" to "e1", "value" to "123"), listOf(Element("textbox", "Security code (CVC)")), "https://a.com/")
                .shouldBeInstanceOf<StepVerdict.Ask>()
        }

        test("a form with a literal card number among placeholders asks") {
            val input = buildJsonObject {
                put("elements", kotlinx.serialization.json.buildJsonArray {
                    add(obj("uid" to "e1", "value" to "[secret:c_number]"))
                    add(obj("uid" to "e2", "value" to "4111111111111111"))
                })
            }
            stepVerdict("fill_form_by_uid", input, listOf(Element("textbox", "Card number"), Element("textbox", "Name on card")), "https://a.com/")
                .shouldBeInstanceOf<StepVerdict.Ask>()
        }

        test("submitting with Enter asks on payment pages and goes to the triage elsewhere") {
            stepVerdict("press_key", obj("key" to "Enter"), emptyList(), "https://a.com/checkout").shouldBeInstanceOf<StepVerdict.Ask>()
            stepVerdict("press_key", obj("key" to "Enter"), emptyList(), "https://a.com/search") shouldBe StepVerdict.Triage
            stepVerdict("press_key", obj("key" to "Tab"), emptyList(), "https://a.com/") shouldBe StepVerdict.Pass
            stepVerdict("press_key", obj("key" to "Escape"), emptyList(), "https://a.com/") shouldBe StepVerdict.Pass
            stepVerdict("type_text", obj("text" to "hi", "submitKey" to "Enter"), emptyList(), "https://a.com/") shouldBe StepVerdict.Triage
        }

        test("uploads and drags go to the triage unless the target says something risky") {
            stepVerdict("upload_file_by_uid", obj("uid" to "e1", "filePath" to "/tmp/x"), listOf(Element("button", "Choose file")), "https://a.com/") shouldBe StepVerdict.Triage
            stepVerdict("drag_by_uid_to_uid", obj("fromUid" to "a", "toUid" to "b"), listOf(Element("listitem", "Card"), Element("list", "Done")), "https://a.com/") shouldBe StepVerdict.Triage
        }
    }

    context("descriptions") {
        test("say what, which element and where") {
            describeStep("click_by_uid", obj("uid" to "e1"), listOf("e1" to Element("button", "Send")), "https://mail.example.com/compose?x=1") shouldBe
                "Click button \"Send\" on mail.example.com/compose"
            describeStep("fill_by_uid", obj("uid" to "e2", "value" to "[secret:bank_password]"), listOf("e2" to Element("textbox", "Password")), "https://bank.com/login")
                .shouldContain("[secret:bank_password]")
            describeStep("click_by_uid", obj("uid" to "e9"), listOf("e9" to null), null) shouldBe "Click element e9 on the current page"
        }
    }
})

private inline fun <T> withClue(clue: String, block: () -> T): T = try {
    block()
} catch (e: AssertionError) {
    throw AssertionError("$clue: ${e.message}", e)
}
