package net.robog.clarity.backend.dedup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import net.robog.clarity.shared.noun.Handle

internal val arbHandle: Arb<Handle> = arbitrary {
    Handle(Arb.enum<Handle.Kind>().bind(), Arb.string(0..16).bind(), Arb.string(0..8).bind().takeIf { it.isNotEmpty() })
}

class NormalizeTest : FunSpec({
    test("emails are lowercased and trimmed") {
        normalizeEmail("  Alice@Example.COM ") shouldBe "alice@example.com"
    }

    test("phone numbers keep digits with a single leading plus") {
        normalizePhone("+1 (555) 010-9999") shouldBe "+15550109999"
        normalizePhone("0044 20 7946 0958") shouldBe "+442079460958"
        normalizePhone("555.0100") shouldBe "+5550100"
    }

    test("whitespace collapses") {
        "  hello \n\t world  ".collapseWhitespace() shouldBe "hello world"
    }

    test("loose handles pick the right normalization") {
        normalizeLooseHandle(" Bob@X.org ") shouldBe "bob@x.org"
        normalizeLooseHandle("(555) 123-4567") shouldBe "+5551234567"
        normalizeLooseHandle(" @Bob ") shouldBe "@bob"
    }

    test("handle normalization is idempotent") {
        checkAll(arbHandle) { h -> h.normalized().normalized() shouldBe h.normalized() }
    }

    test("handle sets are idempotent, deduplicated and order independent") {
        checkAll(Arb.list(arbHandle, 0..6)) { hs ->
            val once = hs.normalizedSet()
            once.normalizedSet() shouldBe once
            hs.reversed().normalizedSet() shouldBe once
        }
    }
})
