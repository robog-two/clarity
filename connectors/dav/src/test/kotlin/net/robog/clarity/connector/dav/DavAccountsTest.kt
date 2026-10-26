package net.robog.clarity.connector.dav

import com.google.crypto.tink.hybrid.internal.TestSealer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import java.io.IOException
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.SealingException
import net.robog.clarity.connector.sdk.SealingKeys
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.Sealing
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord

private const val CONNECTOR = "dav"

private fun sealed(keys: SealingKeys, field: String, plain: String): JsonElement {
    val s = TestSealer.seal(keys.publicKey, CONNECTOR, field, plain)
    return buildJsonObject { put("type", "sealed"); put("algorithm", s.algorithm); put("ciphertext", s.ciphertext) }
}

private fun account(keys: SealingKeys, id: String, label: String, sealedWith: String = id, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(
    mapOf(
        "id" to JsonPrimitive(id), "label" to JsonPrimitive(label),
        "caldavUrl" to JsonPrimitive("https://dav.test/dav/"), "username" to JsonPrimitive("me"),
        "password" to sealed(keys, Sealing.itemField("accounts", sealedWith, "password"), "pw-$id"),
    ) + extra,
)

private fun accountsConfig(vararg accounts: JsonObject) = ConnectorConfig(1, JsonObject(mapOf("accounts" to JsonArray(accounts.toList()))))

private fun config(id: String, label: String) = DavConfig(
    caldavUrl = "https://dav.test/dav/", carddavUrl = null, webdavUrl = null, username = "me", password = "pw",
    email = null, calendars = emptyList(), addressBooks = emptyList(), webdavFolders = emptyList(), id = id, label = label,
)

/** A server with one calendar named [calendar] (path `/dav/cal/me/<path>/`) holding one event. */
private val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
private val soon = stamp.format(java.time.Instant.now().plus(java.time.Duration.ofDays(5)))
private val soonEnd = stamp.format(java.time.Instant.now().plus(java.time.Duration.ofDays(5)).plusSeconds(3600))

private fun fakeServer(path: String, calendar: String, event: String): FakeDav = FakeDav().apply {
    collection("/dav/"); collection("/dav/principals/me/"); collection("/dav/cal/me/")
    collection("/dav/cal/me/$path/"); calendars += "/dav/cal/me/$path/"; names["/dav/cal/me/$path/"] = calendar
    put(
        "/dav/cal/me/$path/$event.ics", "text/calendar",
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN", "BEGIN:VEVENT", "UID:$event", "DTSTAMP:20260301T000000Z", "DTSTART:$soon", "DTEND:$soonEnd", "SUMMARY:$event", "END:VEVENT", "END:VCALENDAR")
            .joinToString("\r\n") + "\r\n",
    )
}

private val unreachable = MockEngine { throw IOException("no route to host") }

class DavAccountsTest : FunSpec({
    val keys = SealingKeys.generate()
    val runtime = ConnectorRuntime(CONNECTOR, keys)

    context("config") {
        test("a legacy flat config is one account, default, with its password sealed under the plain field name") {
            val values = JsonObject(mapOf(
                "baseUrl" to JsonPrimitive("https://cloud.example.com/remote.php/dav"),
                "username" to JsonPrimitive("me"), "password" to sealed(keys, "password", "pw"),
            ))
            val entry = parseDavAccounts(ConnectorConfig(1, values), runtime).single()
            entry.id shouldBe "default"
            entry.result.getOrThrow().run { password shouldBe "pw"; caldavUrl shouldBe "https://cloud.example.com/remote.php/dav" }
        }

        test("an item's password is bound to its id: a value sealed for a1 does not open as a2") {
            val entries = parseDavAccounts(accountsConfig(account(keys, "a1", "iCloud"), account(keys, "a2", "Work", sealedWith = "a1")), runtime)
            entries[0].result.getOrThrow().password shouldBe "pw-a1"
            entries[1].result.exceptionOrNull().shouldBeInstanceOf<SealingException>()
        }

        test("a preset account with calendar and contacts URLs needs no baseUrl; one with no URL at all says so") {
            val ok = account(keys, "a1", "iCloud", extra = mapOf("carddavUrl" to JsonPrimitive("https://contacts.example.com")))
            parseDavAccounts(accountsConfig(ok), runtime).single().result.getOrThrow().carddavUrl shouldBe "https://contacts.example.com"
            val none = JsonObject(account(keys, "a2", "Empty") - "caldavUrl")
            parseDavAccounts(accountsConfig(none), runtime).single().result.exceptionOrNull()!!.message shouldBe "Empty: Server URL is not set"
        }

        test("the schema is a collection of accounts with iCloud, Fastmail and Nextcloud presets") {
            val accounts = (davConfigSchema["properties"] as JsonObject)["accounts"] as JsonObject
            (accounts["x-presets"] as JsonArray).map { ((it as JsonObject)["title"] as JsonPrimitive).content } shouldContainExactly listOf("iCloud", "Fastmail", "Nextcloud")
            val required = ((accounts["items"] as JsonObject)["required"] as JsonArray).map { (it as JsonPrimitive).content }
            required.contains("baseUrl") shouldBe false
        }
    }

    context("source ids") {
        test("a source id carries the account and round-trips; the old bare path is account default") {
            DavRef.parse(sourceIdOf("k3j9x2p1", "/dav/cal/x.ics", "1772445600")) shouldBe DavRef("k3j9x2p1", "/dav/cal/x.ics#1772445600")
            DavRef.parse(sourceIdOf("k3j9x2p1", "/dav/cal/x.ics")) shouldBe DavRef("k3j9x2p1", "/dav/cal/x.ics")
            DavRef.parse("/dav/cal/x.ics#5") shouldBe DavRef("default", "/dav/cal/x.ics#5")
            DavRef.parse(sourceIdOf("default", "/a:b/c.ics")) shouldBe DavRef("default", "/a:b/c.ics")
        }
    }

    context("sync and test") {
        val work = fakeServer("main", "Main", "work-event")
        val home = fakeServer("garden", "Garden", "home-event")
        val engines = mapOf("a1" to work.engine, "a2" to unreachable, "a3" to home.engine)
        val connect = { c: DavConfig -> DavServer(c, engines.getValue(c.id)) }
        val config = accountsConfig(account(keys, "a1", "Work"), account(keys, "a2", "Offline"), account(keys, "a3", "Home"))

        test("each account syncs on its own; a failing one does not stop the others") {
            val pushed = mutableListOf<IngestRecord>()
            val state = InMemoryStateStore()
            val reports = syncAccounts(parseDavAccounts(config, runtime), state, { pushed += it }, connect)

            reports.map { it.result.isSuccess } shouldContainExactly listOf(true, false, true)
            pushed.filterIsInstance<IngestRecord.Upsert>().map { it.sourceId.value } shouldContainExactly
                listOf("a1:/dav/cal/me/main/work-event.ics", "a3:/dav/cal/me/garden/home-event.ics")
            (state.keys().filter { it.startsWith("a1/") }.isNotEmpty() && state.keys().filter { it.startsWith("a3/") }.isNotEmpty()) shouldBe true
        }

        test("the connection test names every account, failing ones first, and is not ok if any failed") {
            val result = testDavAccounts(runtime, config, connect)
            result.ok shouldBe false
            result.message shouldStartWith "Offline: could not connect: no route to host"
            result.message shouldContain " · Work: 1 calendars, 0 address books"
            result.message shouldContain " · Home: 1 calendars, 0 address books"
        }
    }

    context("actions") {
        val work = fakeServer("main", "Main", "work-event")
        val home = fakeServer("garden", "Garden", "home-event")
        val servers = mapOf("a1" to work, "a2" to home)
        val actions = DavActions({ listOf(config("a1", "Work"), config("a2", "Home")) }) { DavServer(it, servers.getValue(it.id).engine) }
        val start = Instant.parse("2026-03-11T09:00:00Z")
        val end = Instant.parse("2026-03-11T10:00:00Z")
        fun create(key: String, calendar: String?) = ProposedAction.CreateEvent(key, "hold", calendar, "Hold $key", start, end)

        suspend fun created(key: String, calendar: String?): String =
            actions.createEvent(create(key, calendar)).shouldBeInstanceOf<ActionOutcome.Done>().ref!!.value

        test("CREATE_EVENT: label/calendar names the account, a bare calendar is the first match, null is the first account") {
            created("c1", "Home/Garden") shouldBe "a2:/dav/cal/me/garden/${uidFor("c1")}.ics"
            created("c2", "Garden") shouldBe "a2:/dav/cal/me/garden/${uidFor("c2")}.ics"
            created("c3", "Main") shouldBe "a1:/dav/cal/me/main/${uidFor("c3")}.ics"
            created("c4", "work/Main") shouldBe "a1:/dav/cal/me/main/${uidFor("c4")}.ics"
            created("c5", null) shouldBe "a1:/dav/cal/me/main/${uidFor("c5")}.ics"
            home.files.keys.count { it.startsWith("/dav/cal/me/garden/clarity-") } shouldBe 2
            work.files.keys.count { it.startsWith("/dav/cal/me/main/clarity-") } shouldBe 3
        }

        test("MOVE_EVENT and RESPOND_TO_INVITE act on the account that holds the event") {
            val move = ProposedAction.MoveEvent("mv", "move", EntityId("e"), Instant.parse("2026-03-13T09:00:00Z"), Instant.parse("2026-03-13T10:00:00Z"), false)
            actions.moveEvent(move, SourceId("a2:/dav/cal/me/garden/home-event.ics")).shouldBeInstanceOf<ActionOutcome.Done>()
            home.text("/dav/cal/me/garden/home-event.ics") shouldContain "DTSTART:20260313T090000Z"
            work.text("/dav/cal/me/main/work-event.ics") shouldContain "DTSTART:$soon"

            actions.moveEvent(move.copy(idempotencyKey = "mv2"), SourceId("gone:/dav/cal/me/garden/home-event.ics"))
                .shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "not set up"
        }
    }
})
