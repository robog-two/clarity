package net.robog.clarity.backend.dedup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import net.robog.clarity.backend.persistence.documentTsQuery
import net.robog.clarity.shared.noun.DocumentPayload

private fun doc(store: String = "webdav:https://cloud.example/dav", path: String = "/Notes/plan.md", excerpt: String = "") =
    DocumentPayload(store = store, path = path, title = "plan", excerpt = excerpt)

class DocumentDedupTest : FunSpec({
    test("paths are normalized to one leading slash, no repeats, no trailing slash") {
        normalizeDocumentPath("Notes//plan.md/") shouldBe "/Notes/plan.md"
        normalizeDocumentPath("  /a\\b/ ") shouldBe "/a/b"
        normalizeDocumentPath("") shouldBe "/"
    }

    test("the dedup key is store plus normalized path, nothing else") {
        val a = doc().dedupKey()
        doc(path = "Notes//plan.md/").dedupKey() shouldBe a
        doc(store = "WebDAV:https://cloud.example/dav/").dedupKey() shouldBe a
        doc(excerpt = "different text").dedupKey() shouldBe a
        doc(path = "/Notes/other.md").dedupKey() shouldNotBe a
        doc(store = "webdav:https://other.example/dav").dedupKey() shouldNotBe a
    }

    test("excerpts are cut to 8 KB of UTF-8 without splitting a character") {
        val text = "é".repeat(5000) // 2 bytes each
        val cut = doc(excerpt = text).normalized().excerpt
        cut.toByteArray().size shouldBe 8192
        cut.all { it == 'é' } shouldBe true
        "a😀".truncateUtf8(4) shouldBe "a" // the emoji needs 4 bytes
    }

    test("document normalization is idempotent") {
        checkAll(Arb.string(0..20), Arb.string(0..20)) { s, p ->
            val d = DocumentPayload(s, p, "t")
            d.normalized().normalized() shouldBe d.normalized()
        }
    }

    test("trailing whitespace and slashes normalize the same way in one pass") {
        normalizeDocumentPath("d /") shouldBe "/d"
        normalizeDocumentPath("/d") shouldBe "/d"
        DocumentPayload("Store / ", "d /", "t").normalized().store shouldBe "store"
    }

    test("search queries become prefix tsqueries") {
        documentTsQuery("Quarterly rep") shouldBe "quarterly & rep:*"
        documentTsQuery("  ;; ") shouldBe null
        documentTsQuery("it's (a) test") shouldBe "it & s & a & test:*"
    }
})
