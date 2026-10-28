package net.robog.clarity.app.ui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.GoodsFile
import net.robog.clarity.app.data.GoodsRepository
import net.robog.clarity.app.data.goodsFileName
import net.robog.clarity.app.data.goodsFilePath
import net.robog.clarity.app.data.goodsMimeType
import net.robog.clarity.app.net.DeviceJwtSigner
import net.robog.clarity.app.net.KtorClarityApi
import net.robog.clarity.app.net.createHttpClient
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.ui.goods.DownloadState
import net.robog.clarity.app.ui.goods.GoodsFormat
import net.robog.clarity.app.ui.goods.GoodsViewModel
import net.robog.clarity.app.ui.goods.MdBlock
import net.robog.clarity.app.ui.goods.MdInline
import net.robog.clarity.app.ui.goods.MdMeta
import net.robog.clarity.app.ui.goods.ReportViewModel
import net.robog.clarity.app.ui.goods.cardFormats
import net.robog.clarity.app.ui.goods.countLine
import net.robog.clarity.app.ui.goods.goodsRows
import net.robog.clarity.app.ui.goods.goodsWhen
import net.robog.clarity.app.ui.goods.parseInline
import net.robog.clarity.app.ui.goods.parseMarkdown
import net.robog.clarity.app.ui.goods.plainText
import net.robog.clarity.app.ui.goods.revisionLine
import net.robog.clarity.app.ui.goods.sourceSite
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.report.ReportCitation
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportFormatOption
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportList
import net.robog.clarity.shared.report.ReportRevisionInfo
import net.robog.clarity.shared.report.ReportStyle
import net.robog.clarity.shared.report.ReportSummary

private fun t(text: String) = MdInline.Text(text)

class MarkdownTest {
    @Test fun frontMatterGivesTheTitleBlock() {
        val doc = parseMarkdown("---\ntitle: \"The Devils (1971)\"\nauthor: Sam\ndate: Oct 28\n---\n\n# Background\n\nText.")
        assertEquals(MdMeta("The Devils (1971)", "Sam", "Oct 28"), doc.meta)
        assertEquals(listOf(MdBlock.Heading(1, listOf(t("Background"))), MdBlock.Paragraph(listOf(t("Text.")))), doc.blocks)
    }

    @Test fun withoutFrontMatterARuleAtTheTopIsARule() {
        val doc = parseMarkdown("---\n\nNot front matter.")
        assertEquals(MdMeta(), doc.meta)
        assertEquals(MdBlock.Rule, doc.blocks.first())
    }

    @Test fun paragraphsJoinTheirLinesAndEndAtABlock() {
        val doc = parseMarkdown("One line\nand the next.\n## Next\nAfter")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(t("One line and the next."))),
                MdBlock.Heading(2, listOf(t("Next"))),
                MdBlock.Paragraph(listOf(t("After"))),
            ),
            doc.blocks,
        )
    }

    @Test fun listsBulletAndNumberedWithNestingAndBlankLines() {
        val doc = parseMarkdown("- one\n  continued\n- two\n  - inner\n\n- three\n\n3. c\n4. d\n\nAfter.")
        val bullets = assertIs<MdBlock.ListBlock>(doc.blocks[0])
        assertNull(bullets.start)
        assertEquals(listOf("one continued", "two", "three"), bullets.items.map { plainText(it.text) })
        val inner = assertIs<MdBlock.ListBlock>(bullets.items[1].children.single())
        assertEquals("inner", plainText(inner.items.single().text))
        val numbered = assertIs<MdBlock.ListBlock>(doc.blocks[1])
        assertEquals(3, numbered.start)
        assertEquals(2, numbered.items.size)
        assertEquals(MdBlock.Paragraph(listOf(t("After."))), doc.blocks[2])
    }

    @Test fun quotesCodeAndTables() {
        val doc = parseMarkdown("> \"A quote\" -- Russell\n> goes on\n\n```\nval x = 1\n```\n\n| A | B |\n|---|---|\n| 1 | *2* |")
        val quote = assertIs<MdBlock.Quote>(doc.blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(t("\"A quote\" -- Russell goes on"))), quote.blocks.single())
        assertEquals(MdBlock.Code("val x = 1"), doc.blocks[1])
        val table = assertIs<MdBlock.Table>(doc.blocks[2])
        assertEquals(2, table.rows.size)
        assertEquals(listOf(MdInline.Emphasis(listOf(t("2")))), table.rows[1][1])
    }

    @Test fun inlineEmphasisStrongCodeLinksAndEscapes() {
        assertEquals(
            listOf(t("Ken Russell’s "), MdInline.Emphasis(listOf(t("The Devils"))), t(" and "), MdInline.Strong(listOf(t("Huxley")))),
            parseInline("Ken Russell’s *The Devils* and **Huxley**"),
        )
        assertEquals(
            listOf(t("See "), MdInline.Link(listOf(t("the BFI")), "https://www.bfi.org.uk/x"), t(".")),
            parseInline("See [the BFI](https://www.bfi.org.uk/x)."),
        )
        assertEquals(listOf(t("a "), MdInline.Code("x*y"), t(" b")), parseInline("a `x*y` b"))
        assertEquals(listOf(t("2 * 3 = 6, *not* bold")), parseInline("2 * 3 = 6, \\*not\\* bold"))
        assertEquals(listOf(t("snake_case_name")), parseInline("snake_case_name"))
        assertEquals(listOf(MdInline.Strong(listOf(MdInline.Emphasis(listOf(t("both")))))), parseInline("***both***"))
        assertEquals(listOf(t("an [unclosed link")), parseInline("an [unclosed link"))
    }

    @Test fun nothingEverThrows() {
        listOf("", "#", "* ", "> ", "```", "|", "[]()", "**", "_", "---\ntitle: x", "1. ", "- - -").forEach { parseMarkdown(it) }
    }
}

class GoodsPresentationTest {
    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-10-28T15:00:00Z")

    @Test fun whenReadsLikeAList() {
        assertEquals("Today, 9:41 am", goodsWhen(Instant.parse("2026-10-28T09:41:00Z"), now, zone))
        assertEquals("Yesterday", goodsWhen(now - 1.days, now, zone))
        assertEquals("Oct 2", goodsWhen(Instant.parse("2026-10-02T10:00:00Z"), now, zone))
        assertEquals("Dec 24, 2025", goodsWhen(Instant.parse("2025-12-24T10:00:00Z"), now, zone))
    }

    @Test fun rowsNewestChangeFirstWithKindAndRevision() {
        val rows = goodsRows(
            listOf(
                ReportSummary("a", ReportKind.DOCUMENT, "Older", now - 3.days, 1, now - 3.days),
                ReportSummary("b", ReportKind.DOCUMENT, "The Devils", now - 4.days, 2, now - 1.days),
            ),
            now, zone,
        )
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertEquals("Document · Yesterday · revision 2", rows[0].detail)
        assertEquals("1 document", countLine(1))
        assertEquals("2 documents", countLine(2))
    }

    @Test fun cardFormatsSkipMarkdownAndKeepUnknownOnes() {
        assertEquals(
            listOf(GoodsFormat("pdf", "PDF"), GoodsFormat("docx", "Word / Pages"), GoodsFormat("odt", "OpenDocument"), GoodsFormat("txt", "Text"), GoodsFormat("epub", "EPUB")),
            cardFormats(listOf("pdf", "docx", "odt", "txt", "md", "epub")),
        )
    }

    @Test fun sourcesShowTheirSite() {
        assertEquals("cdc.gov", sourceSite("https://www.cdc.gov/flu/1918?x=1"))
        assertEquals("en.wikipedia.org", sourceSite("https://en.wikipedia.org/wiki/The_Devils_(film)"))
        assertEquals("not a url", sourceSite("not a url"))
    }

    @Test fun revisionLineSaysWhichAndWhen() {
        assertEquals("Document · Today, 1:00 pm", revisionLine(detail(revisions = 1), now, zone))
        assertEquals("Document · revision 3 · Today, 3:00 pm", revisionLine(detail(revisions = 3), now, zone))
    }

    @Test fun downloadsStayInsideTheGoodsFolder() {
        assertEquals("goods/abc-123/The Devils 1971.pdf", goodsFilePath("abc-123", "The Devils (1971)", "pdf"))
        val evil = goodsFilePath("../../shared_prefs", "../../x/y", "../docx")
        assertEquals("goods/sharedprefs/x y.docx", evil)
        assertFalse(evil.removePrefix("goods/").contains(".."), evil)
        assertEquals("Document", goodsFileName("///"))
        assertEquals("application/pdf", goodsMimeType("pdf"))
        assertEquals("text/plain", goodsMimeType("txt"))
        assertEquals("application/octet-stream", goodsMimeType("zzz"))
    }
}

private fun detail(revisions: Int = 1, sources: List<ReportCitation> = emptyList()) = ReportDetail(
    id = "r1",
    kind = ReportKind.DOCUMENT,
    title = "The Devils",
    createdAt = Instant.parse("2026-10-28T15:00:00Z") - 2.hours,
    revisions = (1..revisions).map { ReportRevisionInfo(it, Instant.parse("2026-10-28T15:00:00Z") - (revisions - it).hours, ReportStyle(), 100) },
    sources = sources,
    formats = listOf(ReportFormatOption("pdf", "PDF"), ReportFormatOption("docx", "Word / Pages")),
)

private class FakeGoods(
    var list: ApiResult<List<ReportSummary>> = ApiResult.Success(emptyList()),
    var detail: ApiResult<ReportDetail> = ApiResult.Success(detail()),
    var source: ApiResult<String> = ApiResult.Success("---\ntitle: Possession and Power\n---\n\n# Background\n\nText."),
    var download: ApiResult<GoodsFile> = ApiResult.Success(GoodsFile(File("x.pdf"), "application/pdf")),
) : GoodsRepository {
    val downloads = mutableListOf<Pair<String, String>>()
    var listCalls = 0
    override suspend fun list() = list.also { listCalls++ }
    override suspend fun detail(id: String) = detail
    override suspend fun source(id: String) = source
    override suspend fun download(id: String, format: String, title: String): ApiResult<GoodsFile> {
        downloads += format to title
        return download
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun main() = Dispatchers.setMain(dispatcher)
    @AfterTest fun reset() = Dispatchers.resetMain()

    private fun vm(goods: FakeGoods) = ReportViewModel(goods, FixedClock(Instant.parse("2026-10-28T15:00:00Z"))).also { it.parseOn = dispatcher }

    @Test fun opensWithTheTextTheFormatsAndTheSources() = runTest(dispatcher) {
        val sources = listOf(ReportCitation("https://www.bfi.org.uk/x", "The Devils at 50", null, T0))
        val vm = vm(FakeGoods(detail = ApiResult.Success(detail(revisions = 2, sources = sources))))
        vm.open("r1")
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals("Possession and Power", s.title, "the front matter's title wins")
        assertTrue(s.subtitle.startsWith("Document · revision 2"))
        assertEquals(listOf("pdf", "docx"), s.formats.map { it.wire })
        assertEquals(sources, s.sources)
        assertEquals(MdBlock.Heading(1, listOf(t("Background"))), s.document!!.blocks.first())
    }

    @Test fun aMissingReportIsAnErrorAndRetryLoadsAgain() = runTest(dispatcher) {
        val goods = FakeGoods(detail = ApiResult.Failure(ApiError.NotFound(null)))
        val vm = vm(goods)
        vm.open("r1")
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        goods.detail = ApiResult.Success(detail())
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("Possession and Power", vm.state.value.title)
    }

    @Test fun withoutTheTextTheDownloadsStillWork() = runTest(dispatcher) {
        val vm = vm(FakeGoods(source = ApiResult.Failure(ApiError.Unreachable("down"))))
        vm.open("r1")
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.error)
        assertNull(s.document)
        assertEquals("Can’t reach your server right now.", s.sourceError)
        assertEquals("The Devils", s.title)
        assertEquals(2, s.formats.size)
    }

    @Test fun aDownloadBecomesAnOpenRequestThenClears() = runTest(dispatcher) {
        val goods = FakeGoods()
        val vm = vm(goods)
        vm.open("r1")
        advanceUntilIdle()
        vm.download("docx", share = true)
        assertEquals(DownloadState.Busy, vm.state.value.downloads["docx"])
        vm.download("docx") // ignored while busy
        advanceUntilIdle()
        assertEquals(listOf("docx" to "Possession and Power"), goods.downloads)
        val open = assertNotNull(vm.state.value.open)
        assertTrue(open.share)
        assertTrue(vm.state.value.downloads.isEmpty())
        vm.opened()
        assertNull(vm.state.value.open)
    }

    @Test fun aFailedDownloadSaysWhy() = runTest(dispatcher) {
        val vm = vm(FakeGoods(download = ApiResult.Failure(ApiError.Http(422, null))))
        vm.open("r1")
        advanceUntilIdle()
        vm.download("pdf")
        advanceUntilIdle()
        assertIs<DownloadState.Failed>(vm.state.value.downloads["pdf"])
        assertNull(vm.state.value.open)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoodsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun main() = Dispatchers.setMain(dispatcher)
    @AfterTest fun reset() = Dispatchers.resetMain()

    private fun reportItem(seq: Long) = FeedItem(FeedItemId("f$seq"), seq, T0, FeedItem.Author.AGENT, cards = listOf(Card.Report("r$seq", "Doc $seq", listOf("pdf"))))

    @Test fun listsAndFetchesAgainWhenANewGoodArrivesInTheFeed() = runTest(dispatcher) {
        val goods = FakeGoods(list = ApiResult.Success(listOf(ReportSummary("r1", ReportKind.DOCUMENT, "The Devils", T0, 1, T0))))
        val feed = ScreenFeed()
        val vm = GoodsViewModel(goods, feed, FixedClock(T0))
        advanceUntilIdle()
        assertEquals(listOf("The Devils"), vm.state.value.rows.map { it.title })
        assertEquals(1, goods.listCalls)
        feed.itemsFlow.value = listOf(reportItem(1))
        advanceUntilIdle()
        assertEquals(2, goods.listCalls)
        feed.itemsFlow.value = listOf(reportItem(1)) // the same cards: no fetch
        advanceUntilIdle()
        assertEquals(2, goods.listCalls)
    }

    @Test fun aFailureKeepsWhatWasLoaded() = runTest(dispatcher) {
        val goods = FakeGoods(list = ApiResult.Success(listOf(ReportSummary("r1", ReportKind.DOCUMENT, "The Devils", T0, 1, T0))))
        val vm = GoodsViewModel(goods, ScreenFeed(), FixedClock(T0))
        advanceUntilIdle()
        goods.list = ApiResult.Failure(ApiError.Unreachable(null))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.rows.size)
        assertEquals("Can’t reach your server right now.", vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
    }
}

class GoodsApiTest {
    @Test fun listDetailSourceAndADownloadToAFile() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val store = FakeSessionStore(signedIn())
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/reports" -> respond(ClarityJson.encodeToString(ReportList.serializer(), ReportList(emptyList())), HttpStatusCode.OK, json)
                "/api/v1/reports/r1" -> respond(ClarityJson.encodeToString(ReportDetail.serializer(), detail()), HttpStatusCode.OK, json)
                "/api/v1/reports/r1/source" -> respond("# Hello", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/markdown; charset=utf-8"))
                "/api/v1/reports/r1/download/pdf" -> respond("%PDF-1.7 fake".encodeToByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                else -> respond("""{"title":"Not found","status":404}""", HttpStatusCode.NotFound, json)
            }
        }
        val api = KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
        assertIs<ApiResult.Success<ReportList>>(api.reports())
        assertEquals("The Devils", assertIs<ApiResult.Success<ReportDetail>>(api.report("r1")).value.title)
        assertEquals("# Hello", assertIs<ApiResult.Success<String>>(api.reportSource("r1")).value)

        val dir = Files.createTempDirectory("goods").toFile()
        val file = File(dir, "goods/r1/The Devils.pdf")
        assertIs<ApiResult.Success<Unit>>(api.downloadReport("r1", "pdf", file))
        assertEquals("%PDF-1.7 fake", file.readText())
        val missing = File(dir, "goods/r1/The Devils.odt")
        assertIs<ApiResult.Failure>(api.downloadReport("r1", "odt", missing))
        assertFalse(missing.exists())
        assertFalse(File(missing.path + ".part").exists())
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true }, "every Goods call is device-authenticated")
    }
}
