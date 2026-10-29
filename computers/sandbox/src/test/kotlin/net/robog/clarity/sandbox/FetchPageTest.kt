package net.robog.clarity.sandbox

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun args(url: String, offset: Int? = null) = JsonObject(
    buildMap {
        put("url", JsonPrimitive(url))
        offset?.let { put("offset", JsonPrimitive(it)) }
    },
)

private const val ARTICLE = """
<html><head><title>The 1918 flu</title><meta name="description" content="How the pandemic spread.">
<script>alert('x')</script><style>body{}</style></head>
<body>
<nav><a href="/home">Home</a> <a href="/about">About</a></nav>
<header>Site banner</header>
<main>
<h1>The 1918 flu</h1>
<p>It killed about <b>50 million</b> people, says the <a href="https://www.cdc.gov/flu/1918">CDC</a>.</p>
<h2>Spread</h2>
<ul><li>Troop ships</li><li>Rail travel</li></ul>
<blockquote>A quoted line.</blockquote>
<table><tr><th>Year</th><th>Deaths</th></tr><tr><td>1918</td><td>50M</td></tr></table>
<p>See <a href="#notes">the notes</a>.</p>
<div hidden>Hidden text</div>
</main>
<footer>Copyright footer</footer>
</body></html>
"""

class FetchPageTest : FunSpec({
    test("only http and https URLs with a host and no user name") {
        fetchableUrl("https://example.org/a b".replace(" ", "%20")).host shouldBe "example.org"
        fetchableUrl(" http://example.org/x/../y ").path shouldBe "/y"
        for (bad in listOf("file:///etc/passwd", "ftp://example.org/", "javascript:alert(1)", "https:///nohost", "https://user:pw@example.org/", "not a url")) {
            shouldThrow<SandboxException> { fetchableUrl(bad) }
        }
    }

    test("HTML becomes readable text: headings, lists, quotes, tables and link URLs; scripts and navigation left out") {
        val page = htmlToText(ARTICLE, "https://example.org/flu")
        page.title shouldBe "The 1918 flu"
        page.description shouldBe "How the pandemic spread."
        val text = page.text
        text shouldStartWith "# The 1918 flu"
        text shouldContain "It killed about 50 million people, says the CDC (https://www.cdc.gov/flu/1918)."
        text shouldContain "## Spread"
        text shouldContain "- Troop ships\n- Rail travel"
        text shouldContain "> A quoted line."
        text shouldContain "Year | Deaths\n1918 | 50M"
        text shouldContain "See the notes."
        for (gone in listOf("alert", "body{}", "Home", "Site banner", "Copyright footer", "Hidden text", "#notes")) text shouldNotContain gone
    }

    test("a page of text at a time, with the offset to read on from") {
        val fetched = FetchedPage("https://example.org/long", Instant.parse("2026-10-29T12:00:00.123Z"), ReadablePage("Long", null, "line\n".repeat(10_000)))
        val first = pageResult(fetched, 0)
        first shouldStartWith "Title: Long\nURL: https://example.org/long\nFetched: 2026-10-29T12:00:00Z\n"
        first shouldContain "Characters 0-16000 of 50000 (more: call fetch_page again with offset=16000)"
        pageResult(fetched, 48_000) shouldContain "Characters 48000-50000 of 50000\n"
        shouldThrow<SandboxException> { pageResult(fetched, 50_000) }.message shouldContain "past the end"
    }

    test("the egress proxy is parsed from its URL") {
        proxySelector("http://egress-sandbox:3128") shouldNotBe null
        proxySelector("nonsense") shouldBe null
    }

    test("fetches over HTTP: redirects, errors, plain text, PDFs refused, paging without fetching again") {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, status: Int, type: String?, body: String, location: String? = null) = server.createContext(path) { ex ->
            if (path == "/article") hits.incrementAndGet()
            type?.let { ex.responseHeaders.add("Content-Type", it) }
            location?.let { ex.responseHeaders.add("Location", it) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        route("/article", 200, "text/html; charset=utf-8", ARTICLE.replace("</main>", "<p>${"word ".repeat(5_000)}</p></main>"))
        route("/moved", 301, null, "", location = "/article")
        route("/loop", 302, null, "", location = "/loop")
        route("/gone", 404, "text/html", "<p>nope</p>")
        route("/notes.txt", 200, "text/plain", "Just text.")
        route("/paper.pdf", 200, "application/pdf", "%PDF-1.4")
        route("/elsewhere", 302, null, "", location = "file:///etc/passwd")
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val tool = FetchPageTool(fetchClient(null)) { Instant.parse("2026-10-29T12:00:00Z") }
            val page = tool.invoke(args("$base/moved"))
            page shouldContain "Title: The 1918 flu"
            page shouldContain "URL: $base/article"
            page shouldContain "Fetched: 2026-10-29T12:00:00Z"
            page shouldContain "(more: call fetch_page again with offset="
            val next = Regex("offset=(\\d+)").find(page)!!.groupValues[1].toInt()
            tool.invoke(args("$base/moved", next)) shouldContain "Characters $next-"
            hits.get() shouldBe 1
            tool.invoke(args("$base/notes.txt")) shouldContain "Just text."
            shouldThrow<SandboxException> { tool.invoke(args("$base/loop")) }.message shouldContain "redirected more than"
            shouldThrow<SandboxException> { tool.invoke(args("$base/gone")) }.message shouldContain "HTTP 404"
            shouldThrow<SandboxException> { tool.invoke(args("$base/paper.pdf")) }.message shouldContain "pdftotext"
            shouldThrow<SandboxException> { tool.invoke(args("$base/elsewhere")) }.message shouldContain "http and https"
            shouldThrow<SandboxException> { FetchPageTool(null).invoke(args("$base/article")) }.message shouldContain "no internet"
        } finally {
            server.stop(0)
        }
    }
})
