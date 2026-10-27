package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SecretDetectorTest : FunSpec({
    val secrets = listOf(
        "my password is hunter2",
        "Password: Tr0ub4dor&3",
        "wifi passphrase = correct-horse-battery",
        "The api key is sk-ant-api03-abcdefghijklmnopqrstuvwx",
        "use sk-or-v1-0123456789abcdef0123456789abcdef",
        "token=ghp_abcdefghijklmnopqrstuvwxyz0123456789",
        "AWS AKIAIOSFODNN7EXAMPLE",
        "Authorization: Bearer abcdefghijklmnop.qrstuvwx",
        "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
        "Your verification code is 482913",
        "Login code: 5521",
        "the code is 482-913",
        "OTP 123456 expires in 5 minutes",
        "my PIN is 4821",
        "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
        "recovery codes: abcd-efgh-ijkl",
        "here: q8Zr3VbN1xKp7TmW2yLc9HdF4sGj6QeA",
    )
    val ordinary = listOf(
        "Dana prefers morning meetings and hates long calls.",
        "The zip code is 94110.",
        "Flight BA 287 leaves at 10:40 from Terminal 5.",
        "Her password was reset last week; she asked me to remind her to pick a new one.",
        "The token is expired, she needs to sign in again.",
        "Booking reference 4KQ7ZP for the hotel in Lisbon.",
        "https://example.com/docs/getting-started/installation-guide-for-linux",
        "Call 415-555-0134 about the lease.",
        "Order number 112-4432123-5512331 shipped on Monday.",
        "commit 3f2a9c1b7e4d5a6f8b9c0d1e2f3a4b5c6d7e8f90",
        "Remember the PIN pad at the gym is broken.",
    )

    secrets.forEach { s ->
        test("flags: $s") {
            SecretDetector.reason(s) shouldNotBe null
            SecretDetector.redact(s) shouldContain SecretDetector.REDACTED
        }
    }

    ordinary.forEach { s ->
        test("leaves alone: $s") {
            SecretDetector.reason(s) shouldBe null
            SecretDetector.redact(s) shouldBe s
        }
    }

    test("redaction keeps the surrounding text") {
        val out = SecretDetector.redact("Dana sent the code: 778899 for the bank, see you at 3")
        out shouldNotContain "778899"
        out shouldContain "Dana sent"
        out shouldContain "see you at 3"
    }
})
