package net.robog.clarity.backend.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.VaultEnvelope

private class FakeClock(var now: Instant = Instant.parse("2026-10-25T12:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(by: kotlin.time.Duration) { now += by }
}

private val b64 = Base64.getUrlEncoder().withoutPadding()

class VaultPureTest : FunSpec({
    context("placeholder parsing") {
        test("finds tokens in order, with hyphens, dots and uuids") {
            Placeholders.tokens("a [secret:bank_login_password] b [secret:code_0f8fad5b-d9cb-469f-a165-70867728950e] c").shouldContainExactly(
                "bank_login_password", "code_0f8fad5b-d9cb-469f-a165-70867728950e",
            )
        }

        test("malformed placeholders are ignored") {
            Placeholders.tokens("[secret:] [secret:a b] [secret] [secret:x_y [Secret:x_y] secret:x_y] [secret:a/b]").shouldContainExactly()
        }

        test("escaped placeholders stay literal and lose the backslash") {
            Placeholders.tokens("""\[secret:bank_login_password]""").shouldContainExactly()
            Placeholders.replace("""type \[secret:bank_login_password] and [secret:bank_login_password]""") { "VALUE" } shouldBe
                "type [secret:bank_login_password] and VALUE"
        }

        test("nested placeholders only match the inner one, and substituted values are not scanned again") {
            Placeholders.tokens("[secret:[secret:a_b]_c]").shouldContainExactly("a_b")
            Placeholders.replace("[secret:a_b]") { "[secret:a_c]" } shouldBe "[secret:a_c]"
            Placeholders.replace("[secret:[secret:a_b]_c]") { "X" } shouldBe "[secret:X_c]"
        }

        test("unresolved tokens are left alone") {
            Placeholders.replace("[secret:nope_x] [secret:a_b]") { if (it == "a_b") "1" else null } shouldBe "[secret:nope_x] 1"
        }

        test("walks nested JSON string values but not keys or non-strings") {
            val input = buildJsonObject {
                put("uid", "e1")
                put("value", "[secret:a_b]")
                put("[secret:a_b]", "key")
                put("n", 5)
                put("elements", buildJsonArray { add(buildJsonObject { put("uid", "e2"); put("value", "pre-[secret:a_c]") }) })
            }
            Placeholders.tokens(input).shouldContainExactly("a_b", "a_c")
            val out = Placeholders.replace(input) { "<$it>" } as JsonObject
            out["value"] shouldBe JsonPrimitive("<a_b>")
            out["[secret:a_b]"] shouldBe JsonPrimitive("key")
            out["n"] shouldBe JsonPrimitive(5)
            ((out["elements"] as JsonArray)[0] as JsonObject)["value"] shouldBe JsonPrimitive("pre-<a_c>")
        }

        test("a token splits into the longest known credential and one of its fields") {
            val known = mapOf(
                "bank" to setOf("login_password"),
                "bank_login" to setOf("password", "username"),
                "card" to setOf("number", "cvc"),
            )
            Placeholders.resolve("bank_login_password", known) shouldBe Placeholders.Ref("bank_login", "password")
            Placeholders.resolve("card_cvc", known) shouldBe Placeholders.Ref("card", "cvc")
            Placeholders.resolve("bank_login_nope", known).shouldBeNull()
            Placeholders.resolve("other_password", known).shouldBeNull()
            Placeholders.codeId("code_abc") shouldBe "abc"
            Placeholders.codeId("code_").shouldBeNull()
            Placeholders.codeId("card_cvc").shouldBeNull()
        }
    }

    context("registrable domains") {
        test("uses the public suffix list") {
            Sites.registrableDomain("https://login.bank.example.com/path?q=1") shouldBe "example.com"
            Sites.registrableDomain("https://www.bank.co.uk") shouldBe "bank.co.uk"
            Sites.registrableDomain("bank.co.uk") shouldBe "bank.co.uk"
            Sites.registrableDomain("https://alice.github.io/x") shouldBe "alice.github.io"
            Sites.registrableDomain("https://EXAMPLE.com.") shouldBe "example.com"
        }

        test("hosts without a registrable domain match only themselves") {
            Sites.registrableDomain("https://localhost:8080/") shouldBe "localhost"
            Sites.registrableDomain("http://192.168.1.5/login") shouldBe "192.168.1.5"
            Sites.registrableDomain("co.uk") shouldBe "co.uk"
        }

        test("look-alike hosts are different sites") {
            Sites.registrableDomain("https://bank.com.evil.io/") shouldBe "evil.io"
            Sites.registrableDomain("https://evil.io/@bank.com") shouldBe "evil.io"
            Sites.registrableDomain("https://bank.com@evil.io/") shouldBe "evil.io"
            Sites.registrableDomain("https://bankk.com") shouldBe "bankk.com"
        }

        test("garbage has no domain; only https counts for secrets") {
            Sites.registrableDomain("").shouldBeNull()
            Sites.registrableDomain("about:blank").shouldBeNull()
            Sites.isHttps("https://x.com") shouldBe true
            Sites.isHttps("http://x.com") shouldBe false
        }
    }

    context("scrubbing") {
        val repl = listOf("hunter2!" to "[secret:bank_login_password]", "4111111111111111" to "[secret:card_number]", "123" to "[secret:card_cvc]")

        test("replaces the raw value") {
            scrubText("password is hunter2! ok", repl) shouldBe "password is [secret:bank_login_password] ok"
        }

        test("replaces url-encoded, html-escaped and json-escaped spellings") {
            scrubText("a=hunter2%21&c=hunter2!", listOf("hunter2!" to "P")) shouldBe "a=P&c=P"
            val quoted = listOf("a\"b<c&d'e" to "Q")
            scrubText("x a%22b%3Cc%26d%27e y", quoted) shouldBe "x Q y"
            scrubText("x a&quot;b&lt;c&amp;d&#x27;e y", quoted) shouldBe "x Q y"
            scrubText("""x a\"b<c&d'e y""", quoted) shouldBe "x Q y"
            scrubText("x a&#34;b&#60;c&#38;d&#39;e y", quoted) shouldBe "x Q y"
        }

        test("replaces card numbers with spaces or dashes") {
            scrubText("4111 1111 1111 1111", repl) shouldBe "[secret:card_number]"
            scrubText("4111-1111-1111-1111", repl) shouldBe "[secret:card_number]"
            scrubText("card 4111111111111111.", repl) shouldBe "card [secret:card_number]."
        }

        test("short digit codes only match as whole numbers") {
            scrubText("cvc 123 and order 91234 and 1234", repl) shouldBe "cvc [secret:card_cvc] and order 91234 and 1234"
        }

        test("longest value first, so overlapping values are fully replaced") {
            val out = scrubText("secretpass1 secretpass", listOf("secretpass" to "A", "secretpass1" to "B"))
            out shouldBe "B A"
        }

        test("values under three characters are not scrubbed") {
            scrubText("ab ab", listOf("ab" to "X")) shouldBe "ab ab"
        }
    }

    context("grant store") {
        test("grants expire, are capped at an hour, and replace earlier ones for the same site") {
            val clock = FakeClock()
            val store = GrantStore(clock)
            store.put("bank", "bank.com", mapOf("password" to "hunter2!"), 3.hours).expiresAt shouldBe clock.now + 60.minutes
            store.hasLiveLogin("bank.com") shouldBe true
            store.hasLiveLogin("other.com") shouldBe false
            val g = store.put("bank", "bank.com", mapOf("password" to "hunter2!"), 5.minutes)
            g.expiresAt shouldBe clock.now + 5.minutes
            store.find("bank", "bank.com").shouldNotBeNull().value("password") shouldBe "hunter2!"
            store.find("bank", "other.com").shouldBeNull()
            store.find("card", "bank.com").shouldBeNull()
            clock.advance(4.minutes + 59.seconds)
            store.find("bank", "bank.com").shouldNotBeNull()
            clock.advance(2.seconds)
            store.find("bank", "bank.com").shouldBeNull()
            store.liveCount() shouldBe 0
        }

        test("a phone that asks for the old five-minute cap gets the task-length grant; shorter asks are kept") {
            grantSeconds(300) shouldBe 3600
            grantSeconds(9999) shouldBe 3600
            grantSeconds(120) shouldBe 120
        }

        test("released values keep being scrubbed after the grant is gone, then are forgotten") {
            val clock = FakeClock()
            val store = GrantStore(clock)
            store.put("bank", "bank.com", mapOf("password" to "hunter2!"), 1.minutes)
            clock.advance(2.minutes)
            store.find("bank", "bank.com").shouldBeNull()
            store.scrub("value hunter2!") shouldBe "value [secret:bank_password]"
            clock.advance(GrantStore.SCRUB_RETENTION + 1.minutes)
            store.scrub("value hunter2!") shouldBe "value hunter2!"
        }

        test("revoking drops live grants; code grants are found by held id; toString shows no value") {
            val clock = FakeClock()
            val store = GrantStore(clock)
            val g = store.put("bank", "bank.com", mapOf("password" to "hunter2!"), 1.minutes)
            g.toString() shouldNotContain "hunter2"
            store.revoke("bank")
            store.find("bank", "bank.com").shouldBeNull()
            store.put("code", "bank.com", mapOf("held-1" to "424242"), 1.minutes)
            store.findCode("held-1").shouldNotBeNull().value("held-1") shouldBe "424242"
            store.scrub("424242") shouldBe "[secret:code_held-1]"
        }
    }

    context("request, key and envelope sanity") {
        val fields = listOf(FieldSpec("username", "Username", FieldType.USERNAME), FieldSpec("password", "Password", FieldType.PASSWORD))

        test("requests need slugs, fields, a site and a purpose") {
            requestProblem("bank_login", fields, "bank.com", "log in").shouldBeNull()
            requestProblem("Bank", fields, "bank.com", "log in").shouldNotBeNull()
            requestProblem("code_x", fields, "bank.com", "log in").shouldNotBeNull()
            requestProblem("bank_login", emptyList(), "bank.com", "log in").shouldNotBeNull()
            requestProblem("bank_login", fields + fields[0], "bank.com", "log in").shouldNotBeNull()
            requestProblem("bank_login", fields, "", "log in").shouldNotBeNull()
            requestProblem("bank_login", fields, "bank.com", " ").shouldNotBeNull()
        }

        test("hints may only be the allowed non-secret keys") {
            hintsProblem(mapOf("username" to "dana"), fields).shouldBeNull()
            hintsProblem(mapOf("last4" to "1111", "brand" to "visa", "expiry_month" to "12"), fields).shouldBeNull()
            hintsProblem(mapOf("password" to "hunter2"), fields).shouldNotBeNull()
            hintsProblem(mapOf("last4" to "11111"), fields).shouldNotBeNull()
            hintsProblem(mapOf("username" to "x".repeat(65)), fields).shouldNotBeNull()
        }

        test("vault keys must be P-256 SPKI in base64url") {
            val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            vaultKeyProblem(RegisterVaultKey(b64.encodeToString(ec.public.encoded))).shouldBeNull()
            vaultKeyProblem(RegisterVaultKey("not base64 !")).shouldNotBeNull()
            vaultKeyProblem(RegisterVaultKey(b64.encodeToString(ByteArray(10)))).shouldNotBeNull()
            val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
            vaultKeyProblem(RegisterVaultKey(b64.encodeToString(p384.public.encoded))).shouldNotBeNull()
            vaultKeyProblem(RegisterVaultKey(b64.encodeToString(ec.public.encoded), alg = "rot13")).shouldNotBeNull()
        }

        test("envelopes must be well formed and bound to their request") {
            val epk = b64.encodeToString(byteArrayOf(4) + ByteArray(64))
            val ok = VaultEnvelope(DeviceId("d"), epk = epk, iv = b64.encodeToString(ByteArray(12)), ciphertext = b64.encodeToString(ByteArray(40)), context = "r1")
            envelopeProblem(ok, "r1").shouldBeNull()
            envelopeProblem(ok, "r2").shouldNotBeNull()
            envelopeProblem(ok.copy(iv = b64.encodeToString(ByteArray(8))), "r1").shouldNotBeNull()
            envelopeProblem(ok.copy(epk = b64.encodeToString(ByteArray(65))), "r1").shouldNotBeNull()
            envelopeProblem(ok.copy(ciphertext = "***"), "r1").shouldNotBeNull()
            envelopeProblem(ok.copy(alg = "x"), "r1").shouldNotBeNull()
        }
    }

    context("tool descriptions never carry values") {
        test("a credential is described by name, kind, site, hints and placeholders") {
            val meta = net.robog.clarity.shared.vault.CredentialMeta(
                net.robog.clarity.shared.vault.CredentialId("1"), "bank_login", net.robog.clarity.shared.vault.CredentialKind.LOGIN, "bank.com", "pay bills",
                listOf(FieldSpec("username", "Username", FieldType.USERNAME), FieldSpec("password", "Password", FieldType.PASSWORD)),
                mapOf("username" to "dana"), Instant.parse("2026-10-25T12:00:00Z"),
            )
            val text = describeCredential(meta)
            text shouldContain "[secret:bank_login_password]"
            text shouldContain "username=dana"
        }
    }
})
