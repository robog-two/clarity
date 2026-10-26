package net.robog.clarity.connector.dav

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** One stored resource of the fake server. */
class DavFile(var etag: String, var contentType: String, var bytes: ByteArray, val collection: Boolean = false, var version: Int = 0)

/**
 * A small in-memory CalDAV/CardDAV/WebDAV server behind a Ktor [MockEngine] (no sockets). It
 * answers what the connector sends: PROPFIND, REPORT (sync-collection and multiget), GET, PUT, OPTIONS.
 * Collections listed in [syncCollections] support `sync-collection`; the rest only ETag listing.
 */
class FakeDav(private val autoSchedule: Boolean = true) {
    val files = linkedMapOf<String, DavFile>()
    val syncCollections = mutableSetOf<String>()
    val requests = mutableListOf<String>()
    private var tokenCounter = 1
    private var etagCounter = 100

    var syncTokenIssued: String? = null
    val syncReportTokens = mutableListOf<String?>()

    fun collection(path: String) { files[path] = DavFile("", "httpd/unix-directory", ByteArray(0), collection = true) }

    fun put(path: String, contentType: String, text: String): String =
        put(path, contentType, text.toByteArray())

    fun put(path: String, contentType: String, bytes: ByteArray): String {
        val etag = "\"e${etagCounter++}\""
        files[path] = DavFile(etag, contentType, bytes, version = tokenCounter)
        tokenCounter++
        return etag
    }

    val deletedPaths = mutableListOf<String>()

    fun delete(path: String) { files.remove(path); deletedPaths += path; tokenCounter++ }

    fun text(path: String) = files.getValue(path).bytes.decodeToString()

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        requests += "${request.method.value} $path"
        val xml = headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8")
        when (request.method.value) {
            "OPTIONS" -> respond("", HttpStatusCode.OK, headersOf("DAV", if (autoSchedule) "1, 3, calendar-access, calendar-auto-schedule, addressbook" else "1, 3, calendar-access"))
            "PROPFIND" -> {
                if (path == "/.well-known/caldav" || path == "/.well-known/carddav") return@MockEngine respond("", HttpStatusCode.NotFound)
                val depth = request.headers["Depth"] ?: "0"
                val self = files[path] ?: files[path.trimEnd('/') + "/"] ?: virtual(path)
                    ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                val members = if (depth == "0") emptyList() else files.filterKeys { k ->
                    k != path && k.startsWith(path) && k.removePrefix(path).trimEnd('/').let { it.isNotEmpty() && '/' !in it }
                }.toList()
                val responses = (listOf(path to self) + members).joinToString("") { (p, f) -> response(p, f) }
                respond(multistatus(responses), HttpStatusCode.MultiStatus, xml)
            }
            "REPORT" -> when {
                "sync-collection" in body -> {
                    val token = Regex("<[^>]*sync-token[^>]*>([^<]*)<").find(body)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
                    syncReportTokens += token
                    if (token != null && token != "tok-${tokenCounter - 1}" && !token.startsWith("tok-")) return@MockEngine respond("", HttpStatusCode.Forbidden)
                    val since = token?.removePrefix("tok-")?.toIntOrNull() ?: 0
                    val members = files.filterKeys { it != path && it.startsWith(path) && !files.getValue(it).collection && files.getValue(it).version >= since }
                    syncTokenIssued = "tok-$tokenCounter"
                    val gone = deletedPaths.filter { it.startsWith(path) }
                    deletedPaths.removeAll(gone)
                    val changed = members.entries.joinToString("") { (p, f) -> response(p, f) } +
                        gone.joinToString("") { "<D:response><D:href>$it</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>" }
                    respond(multistatus(changed, "<D:sync-token>$syncTokenIssued</D:sync-token>"), HttpStatusCode.MultiStatus, xml)
                }
                "multiget" in body -> {
                    val hrefs = Regex("<[^>]*href[^>]*>([^<]*)<").findAll(body).map { it.groupValues[1] }.toList()
                    val data = hrefs.mapNotNull { h -> files[h]?.let { h to it } }.joinToString("") { (h, f) ->
                        val tag = if ("addressbook" in body) "<C:address-data xmlns:C=\"urn:ietf:params:xml:ns:carddav\">" else "<C:calendar-data xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                        val end = if ("addressbook" in body) "</C:address-data>" else "</C:calendar-data>"
                        "<D:response><D:href>$h</D:href><D:propstat><D:prop><D:getetag>${f.etag}</D:getetag>$tag${escape(f.bytes.decodeToString())}$end</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                    }
                    respond(multistatus(data), HttpStatusCode.MultiStatus, xml)
                }
                else -> respond("", HttpStatusCode.NotImplemented)
            }
            "GET" -> files[path]?.takeIf { !it.collection }?.let { f ->
                respond(f.bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf(f.contentType), HttpHeaders.ETag to listOf(f.etag)))
            } ?: respond("", HttpStatusCode.NotFound)
            "PUT" -> {
                val existing = files[path]
                if (request.headers[HttpHeaders.IfNoneMatch] == "*" && existing != null) return@MockEngine respond("", HttpStatusCode.PreconditionFailed)
                request.headers[HttpHeaders.IfMatch]?.let { if (existing == null || existing.etag != it) return@MockEngine respond("", HttpStatusCode.PreconditionFailed) }
                val etag = put(path, request.headers[HttpHeaders.ContentType] ?: "application/octet-stream", body)
                respond("", if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, etag))
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    /** Principal and home-set resources that exist only to be discovered. */
    private fun virtual(path: String): DavFile? = if (path == "/dav/" || path == "/dav/principals/me/") collection(path).let { files[path] } else null

    private fun response(path: String, f: DavFile): String {
        val props = StringBuilder()
        if (f.collection) {
            props.append("<D:resourcetype><D:collection/>")
            if (path in calendars) props.append("<C:calendar xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>")
            if (path in addressBooks) props.append("<A:addressbook xmlns:A=\"urn:ietf:params:xml:ns:carddav\"/>")
            props.append("</D:resourcetype>")
            if (path in calendars || path in addressBooks) props.append("<D:displayname>${names[path] ?: path}</D:displayname>")
            if (path in calendars) props.append("<C:supported-calendar-component-set xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><C:comp name=\"VEVENT\"/></C:supported-calendar-component-set>")
            if (path in syncCollections) props.append("<D:supported-report-set><D:supported-report><D:report><D:sync-collection/></D:report></D:supported-report></D:supported-report-set>")
            if (path == "/dav/") props.append("<D:current-user-principal><D:href>/dav/principals/me/</D:href></D:current-user-principal>")
            if (path == "/dav/principals/me/") {
                props.append("<C:calendar-home-set xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><D:href>/dav/cal/me/</D:href></C:calendar-home-set>")
                props.append("<A:addressbook-home-set xmlns:A=\"urn:ietf:params:xml:ns:carddav\"><D:href>/dav/card/me/</D:href></A:addressbook-home-set>")
                props.append("<C:calendar-user-address-set xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><D:href>mailto:me@example.com</D:href></C:calendar-user-address-set>")
            }
        } else {
            props.append("<D:resourcetype/><D:getetag>${f.etag}</D:getetag><D:getcontenttype>${f.contentType}</D:getcontenttype><D:getcontentlength>${f.bytes.size}</D:getcontentlength>")
            props.append("<D:getlastmodified>Tue, 10 Mar 2026 09:00:00 GMT</D:getlastmodified>")
        }
        return "<D:response><D:href>$path</D:href><D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    val calendars = mutableSetOf<String>()
    val addressBooks = mutableSetOf<String>()
    val names = mutableMapOf<String, String>()

    private fun multistatus(responses: String, extra: String = "") =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">$responses$extra</D:multistatus>"

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
