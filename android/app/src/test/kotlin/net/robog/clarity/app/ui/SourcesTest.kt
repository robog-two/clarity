package net.robog.clarity.app.ui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SourcesRepository
import net.robog.clarity.app.data.dispositionFilename
import net.robog.clarity.app.net.DeviceJwtSigner
import net.robog.clarity.app.net.KtorClarityApi
import net.robog.clarity.app.net.SourceFile
import net.robog.clarity.app.net.createHttpClient
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.ui.sources.FileState
import net.robog.clarity.app.ui.sources.HandOver
import net.robog.clarity.app.ui.sources.canView
import net.robog.clarity.app.ui.sources.handOverNote
import net.robog.clarity.app.ui.sources.SourceViewModel
import net.robog.clarity.app.ui.sources.attachmentLine
import net.robog.clarity.app.ui.sources.fileError
import net.robog.clarity.app.ui.sources.fileSize
import net.robog.clarity.app.ui.sources.isWebAddress
import net.robog.clarity.app.ui.sources.momentText
import net.robog.clarity.app.ui.sources.safeFileName
import net.robog.clarity.app.ui.sources.sourceAttachments
import net.robog.clarity.app.ui.sources.sourceError
import net.robog.clarity.app.ui.sources.sourceFacts
import net.robog.clarity.app.ui.sources.sourceItemLine
import net.robog.clarity.app.ui.sources.sourceKind
import net.robog.clarity.app.ui.sources.sourceText
import net.robog.clarity.app.ui.sources.sourcesHeading
import net.robog.clarity.app.ui.sources.typeLabel
import net.robog.clarity.app.ui.sources.whenText
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.source.SourceAttachment
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceKind
import net.robog.clarity.shared.source.SourceView

private val newYork = TimeZone.of("America/New_York")

private val message = SourceView.Message(
    id = "m1", title = "Lunch", channel = "email", from = "Sam Example <sam@example.com>", to = listOf("me@example.com", "kim@example.com"),
    sentAt = Instant.parse("2026-10-25T15:00:00Z"), account = "me@example.com", body = "Noon.",
    attachments = listOf(SourceAttachment("2", "menu.pdf", "application/pdf", 1_240_000), SourceAttachment("1.3", null, "image/png", null)),
)

private val event = SourceView.Event(
    id = "e1", title = "Dentist", calendar = "Home", start = Instant.parse("2026-10-27T14:00:00Z"), end = Instant.parse("2026-10-27T15:30:00Z"),
    location = "12 Main Street", description = "Bring forms.", link = "https://example.com/e", repeats = "every 6 months",
)

class SourcePresentationTest {
    @Test fun aMessageShowsWhoWhenAndWhere() {
        assertEquals("Email", sourceKind(message))
        assertEquals("Message", sourceKind(message.copy(channel = "sms")))
        val facts = sourceFacts(message, newYork)
        assertEquals(listOf("From", "To", "Date", "Account"), facts.map { it.label })
        assertEquals("me@example.com, kim@example.com", facts[1].value)
        assertEquals("Sun Oct 25, 2026 at 11:00 am", facts[2].value)
        assertEquals("Your number", sourceFacts(message.copy(channel = "sms"), newYork).last().label)
    }

    @Test fun anEventShowsItsTimesOnThePhonesClock() {
        assertEquals("Tue Oct 27, 2026, 10:00 am to 11:30 am", whenText(event, newYork))
        val overnight = event.copy(end = Instant.parse("2026-10-28T14:00:00Z"))
        assertEquals("Tue Oct 27, 2026 at 10:00 am to Wed Oct 28, 2026 at 10:00 am", whenText(overnight, newYork))
        val facts = sourceFacts(event, newYork)
        assertEquals(listOf("When", "Where", "Repeats", "Calendar", "Link"), facts.map { it.label })
    }

    @Test fun anAllDayEventIsDatesNotTimes() {
        val one = event.copy(allDay = true, start = Instant.parse("2026-10-27T00:00:00Z"), end = Instant.parse("2026-10-28T00:00:00Z"))
        assertEquals("Tue Oct 27, 2026, all day", whenText(one, newYork))
        val three = one.copy(end = Instant.parse("2026-10-30T00:00:00Z"))
        assertEquals("Tue Oct 27, 2026 to Thu Oct 29, 2026", whenText(three, newYork))
    }

    @Test fun textAndFilesBelongToTheirKinds() {
        assertEquals("Message" to "Noon.", sourceText(message)?.let { it.label to it.text })
        assertEquals("Details", sourceText(event)?.label)
        assertNull(sourceText(event.copy(description = null)))
        assertNull(sourceText(SourceView.Document("d", "T", "/p", excerpt = " ")))
        assertEquals(2, sourceAttachments(message).size)
        assertTrue(sourceAttachments(event).isEmpty())
    }

    @Test fun aDocumentAndAContactShowWhatTheyHave() {
        val doc = SourceView.Document("d", "Taxes", "/Taxes/2025.pdf", "application/pdf", 2048, Instant.parse("2026-10-25T15:00:00Z"), "x")
        assertEquals(listOf("Path", "Type", "Size", "Changed"), sourceFacts(doc, newYork).map { it.label })
        assertEquals("2 KB", sourceFacts(doc, newYork)[2].value)
        val contact = SourceView.Contact("c", "Sam Example", "Example Co", listOf("sam@example.com", "+15550100"))
        assertEquals(listOf("Company" to "Example Co", "Email" to "sam@example.com", "Number" to "+15550100"), sourceFacts(contact, newYork).map { it.label to it.value })
    }

    @Test fun sizesAndTypesReadInWords() {
        assertEquals("12 bytes", fileSize(12))
        assertEquals("340 KB", fileSize(340 * 1024L))
        assertEquals("1.2 MB", fileSize(1_300_000))
        assertEquals("PDF", typeLabel("application/pdf"))
        assertEquals("Image", typeLabel("image/png"))
        assertEquals("File", typeLabel(null))
        assertEquals("ZIP", typeLabel("application/zip"))
        assertEquals("PDF, 1.2 MB", attachmentLine(message.attachments[0]))
        assertEquals("Image", attachmentLine(message.attachments[1]))
    }

    @Test fun aFileNameCannotClimbOutOfTheCacheOrHide() {
        assertEquals("menu.pdf", safeFileName("menu.pdf", "2"))
        assertEquals("passwd", safeFileName("../../etc/passwd", "2"))
        assertEquals("evil.txt", safeFileName("..\\..\\evil.txt", "2"))
        assertEquals("hidden", safeFileName(".hidden", "2"))
        assertEquals("a b.pdf", safeFileName("a\u0000 b?.pdf", "2"))
        assertEquals("a b.pdf", safeFileName("a\u0000 b?.pdf", "2"))
        assertEquals("attachment-13", safeFileName(null, "1.3"))
        assertEquals("attachment-2", safeFileName("   ", "2"))
        assertTrue(safeFileName("x".repeat(500), "1").length <= 80)
        // The extension survives a long name, and the bytes stay under the file system's limit.
        val long = safeFileName("é".repeat(300) + ".pdf", "1")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray().size <= 200)
        assertEquals(80, safeFileName("a".repeat(300) + ".pdf", "1").length)
        // Bidi overrides and other format characters are gone.
        assertEquals("fdp.exe", safeFileName("\u202Efdp.exe", "1"))
        assertEquals("ab.txt", safeFileName("a\u200Bb\u2066.txt", "1"))
        assertEquals("pdf", safeFileName("\u202E.pdf", "2"))
    }

    @Test fun onlyWebAddressesGoToTheBrowser() {
        assertTrue(isWebAddress("https://example.com/a?b=1"))
        assertTrue(isWebAddress("HTTP://example.com"))
        assertFalse(isWebAddress("javascript:alert(1)"))
        assertFalse(isWebAddress("intent://x#Intent;end"))
        assertFalse(isWebAddress("https://exa mple.com"))
        assertFalse(isWebAddress("file:///etc/passwd"))
    }

    @Test fun theCardsRowsAndHeading() {
        val entity = SourceItem.Entity("m1", SourceKind.MESSAGE, "Lunch", "Sam Example · 2026-10-25 (Sun)", attachments = 2)
        assertEquals("Sam Example · 2026-10-25 (Sun), 2 files", sourceItemLine(entity, newYork))
        // With the raw time the phone shows it on its own clock.
        val timed = entity.copy(by = "Sam Example", at = Instant.parse("2026-10-26T01:00:00Z"))
        assertEquals("Sam Example · Sun Oct 25, 9:00 pm, 2 files", sourceItemLine(timed, newYork))
        assertEquals("Mon Oct 26, 1:00 am, 2 files", sourceItemLine(timed.copy(by = ""), TimeZone.UTC))
        // An all-day event is its date, whatever the zone.
        val allDay = SourceItem.Entity("e1", SourceKind.EVENT, "Holiday", "Tue Oct 27", day = LocalDate(2026, 10, 27))
        assertEquals("Tue Oct 27", sourceItemLine(allDay, newYork))
        assertEquals("Tue Oct 27", sourceItemLine(allDay, TimeZone.of("Pacific/Auckland")))
        assertEquals("", sourceItemLine(SourceItem.Unknown, newYork))
        assertEquals("1 file", sourceItemLine(entity.copy(sub = "", attachments = 1), newYork))
        assertEquals("", sourceItemLine(entity.copy(sub = "", attachments = 0), newYork))
        assertEquals("example.com", sourceItemLine(SourceItem.Web("https://example.com/x", "T", "example.com")))
        assertEquals("1 source", sourcesHeading(Card.Sources(listOf(entity))))
        assertEquals("2 sources", sourcesHeading(Card.Sources(listOf(entity, entity))))
        assertEquals("1 source", sourcesHeading(Card.Sources(listOf(entity, SourceItem.Unknown))))
    }

    @Test fun onlySafeTypesAreOpenedDirectly() {
        for (ok in listOf("application/pdf", "image/png", "image/jpeg", "text/plain; charset=utf-8", "audio/mpeg", "video/mp4", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text")) assertTrue(canView(ok), ok)
        for (no in listOf("application/vnd.android.package-archive", "application/octet-stream", null, "", "text/html", "image/svg+xml", "application/zip",
            "application/x-sh", "application/java-archive", "text/x-shellscript")) assertFalse(canView(no), no.toString())
    }

    @Test fun aLongTextSaysShortened() {
        assertTrue(sourceText(message.copy(truncated = true))!!.truncated)
        assertFalse(sourceText(message)!!.truncated)
        assertTrue(sourceText(event.copy(truncated = true))!!.truncated)
    }

    @Test fun handOverNotes() {
        assertNull(handOverNote(HandOver.Opened))
        assertEquals("No app can open this file", handOverNote(HandOver.NoApp))
        assertEquals("Couldn’t save this file. Try again.", handOverNote(HandOver.Failed))
    }

    @Test fun momentsAreSpelledOutInFull() {
        assertEquals("Mon Oct 26, 2026 at 8:00 pm", momentText(Instant.parse("2026-10-27T00:00:00Z"), newYork))
    }

    @Test fun theSourcesCardIsNoteTextForAnOlderApp() {
        val text = ClarityJson.encodeToString(Card.serializer(), Card.Sources(listOf(SourceItem.Entity("m1", title = "Lunch")), "Sources. Lunch."))
        assertEquals(Card.Unknown("Sources. Lunch."), ClarityJson.decodeFromString(Card.serializer(), text.replace("\"type\":\"sources\"", "\"type\":\"sources_v2\"")))
        assertEquals(Card.Sources(listOf(SourceItem.Entity("m1", title = "Lunch")), "Sources. Lunch."), ClarityJson.decodeFromString(Card.serializer(), text))
    }

    @Test fun errorsSaySomethingPlain() {
        assertEquals("Can’t find this. It may have been removed.", sourceError(ApiError.NotFound(null)))
        assertEquals("The mail server no longer has this file.", fileError(ApiError.NotFound(null)))
        assertEquals("This file is too big to open here.", fileError(ApiError.Http(413, null)))
        assertEquals("The mail server took too long. Try again.", fileError(ApiError.Http(504, null)))
        assertEquals("Can’t reach server. Check connection.", fileError(ApiError.Unreachable(null)))
    }

    @Test fun theFileNameInADispositionHeader() {
        assertEquals("menu.pdf", dispositionFilename("""attachment; filename="menu.pdf""""))
        assertEquals("menu.pdf", dispositionFilename("attachment; filename=menu.pdf"))
        assertEquals("Menü 1.pdf", dispositionFilename("attachment; filename=\"menu.pdf\"; filename*=UTF-8''Men%C3%BC%201.pdf"))
        assertEquals("a+b.pdf", dispositionFilename("attachment; filename*=UTF-8''a+b.pdf"))
        assertEquals("say \"hi\".txt", dispositionFilename("""attachment; filename="say \"hi\".txt""""))
        assertNull(dispositionFilename("attachment"))
    }
}

private class FakeSources(
    var view: ApiResult<SourceView> = ApiResult.Success(message),
    var file: ApiResult<SourceFile> = ApiResult.Success(SourceFile(byteArrayOf(1, 2, 3), "application/pdf", "from-server.pdf")),
) : SourcesRepository {
    val attachmentCalls = mutableListOf<Pair<String, String>>()
    override suspend fun view(id: String) = view
    override suspend fun attachment(entityId: String, part: String): ApiResult<SourceFile> {
        attachmentCalls += entityId to part
        return file
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SourceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun main() = Dispatchers.setMain(dispatcher)
    @AfterTest fun reset() = Dispatchers.resetMain()

    @Test fun opensTheRecordOnceAndRetryLoadsAgain() = runTest(dispatcher) {
        val sources = FakeSources(view = ApiResult.Failure(ApiError.NotFound(null)))
        val vm = SourceViewModel(sources)
        assertTrue(vm.state.value.loading)
        vm.open("m1")
        advanceUntilIdle()
        assertEquals("Can’t find this. It may have been removed.", vm.state.value.error)
        sources.view = ApiResult.Success(message)
        vm.open("m1")
        advanceUntilIdle()
        assertEquals("Can’t find this. It may have been removed.", vm.state.value.error)
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(message, vm.state.value.view)
        assertFalse(vm.state.value.loading)
    }

    @Test fun anAttachmentIsFetchedOnTapAndHandedOverUnderTheMessagesOwnName() = runTest(dispatcher) {
        val sources = FakeSources()
        val vm = SourceViewModel(sources)
        vm.open("m1")
        advanceUntilIdle()
        vm.download("2")
        assertEquals(FileState.Loading, vm.state.value.files["2"])
        vm.download("2") // a second tap while it is on its way does nothing
        advanceUntilIdle()
        assertEquals(listOf("m1" to "2"), sources.attachmentCalls)
        val open = vm.state.value.open
        assertEquals("menu.pdf", open?.name)
        assertEquals("2", open?.part)
        assertContentEquals(byteArrayOf(1, 2, 3), open?.file?.bytes)
        assertTrue(vm.state.value.files.isEmpty())
        vm.opened(HandOver.Opened)
        assertNull(vm.state.value.open)
        assertNull(vm.state.value.note)
    }

    @Test fun aFileWithoutANameUsesTheServersOrAPlainOne() = runTest(dispatcher) {
        val sources = FakeSources()
        val vm = SourceViewModel(sources)
        vm.open("m1")
        advanceUntilIdle()
        vm.download("1.3")
        advanceUntilIdle()
        assertEquals("from-server.pdf", vm.state.value.open?.name)
    }

    @Test fun aFailureStaysOnItsRowAndCanBeTriedAgain() = runTest(dispatcher) {
        val sources = FakeSources(file = ApiResult.Failure(ApiError.Http(504, null)))
        val vm = SourceViewModel(sources)
        vm.open("m1")
        advanceUntilIdle()
        vm.download("2")
        advanceUntilIdle()
        assertEquals(FileState.Failed("The mail server took too long. Try again."), vm.state.value.files["2"])
        assertNull(vm.state.value.open)
        sources.file = ApiResult.Success(SourceFile(byteArrayOf(9), null, null))
        vm.download("2")
        advanceUntilIdle()
        assertEquals(2, sources.attachmentCalls.size)
        assertTrue(vm.state.value.files.isEmpty())
    }

    @Test fun onlyAPartOfTheOpenMessageCanBeFetched() = runTest(dispatcher) {
        val sources = FakeSources()
        val vm = SourceViewModel(sources)
        vm.download("2") // nothing open yet
        vm.open("m1")
        advanceUntilIdle()
        vm.download("99")
        advanceUntilIdle()
        assertTrue(sources.attachmentCalls.isEmpty())
    }

    @Test fun nothingToOpenTheFileWithIsSaidOnce() = runTest(dispatcher) {
        val vm = SourceViewModel(FakeSources())
        vm.open("m1")
        advanceUntilIdle()
        vm.download("2")
        advanceUntilIdle()
        vm.opened(HandOver.NoApp)
        assertEquals("No app can open this file", vm.state.value.note)
        vm.noteShown()
        assertNull(vm.state.value.note)
        vm.download("2")
        advanceUntilIdle()
        vm.opened(HandOver.Failed)
        assertEquals("Couldn’t save this file. Try again.", vm.state.value.note)
    }
}

class SourcesApiTest {
    @Test fun recordAndAttachmentOverTheDeviceRoutes() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val store = FakeSessionStore(signedIn())
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/sources/m1" -> respond(ClarityJson.encodeToString(SourceView.serializer(), message), HttpStatusCode.OK, json)
                "/api/v1/messages/m1/attachments/1.3" -> respond(
                    byteArrayOf(7, 8), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/pdf; charset=binary"), HttpHeaders.ContentDisposition to listOf("attachment; filename=\"menu.pdf\"")),
                )
                "/api/v1/messages/m1/attachments/9" -> respond("""{"title":"Too big","status":413}""", HttpStatusCode.PayloadTooLarge, json)
                else -> respond("""{"title":"Not found","status":404}""", HttpStatusCode.NotFound, json)
            }
        }
        val api = KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
        assertEquals(message, assertIs<ApiResult.Success<SourceView>>(api.source("m1")).value)
        val file = assertIs<ApiResult.Success<SourceFile>>(api.attachment("m1", "1.3")).value
        assertContentEquals(byteArrayOf(7, 8), file.bytes)
        assertEquals("application/pdf", file.mimeType)
        assertEquals("menu.pdf", file.filename)
        assertEquals(413, assertIs<ApiError.Http>(assertIs<ApiResult.Failure>(api.attachment("m1", "9")).error).status)
        assertIs<ApiError.NotFound>(assertIs<ApiResult.Failure>(api.source("nope")).error)
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true }, "every source call is device-authenticated")
    }
}
