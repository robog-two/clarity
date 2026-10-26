package net.robog.clarity.connector.dav

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavAddressBook
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.DomainAuthProvider
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.UrlUtils
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.withTrailingSlash
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.CalendarUserAddressSet
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.carddav.AddressbookHomeSet
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.GetContentType
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.GetLastModified
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.SupportedReportSet
import at.bitfire.dav4jvm.property.webdav.SyncToken
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.utils.io.core.use
import kotlinx.coroutines.flow.toList
import kotlin.time.Instant

/** A collection found by discovery. */
data class DavCollectionInfo(val url: Url, val name: String, val supportsSync: Boolean)

data class Discovery(
    val calendars: List<DavCollectionInfo>,
    val addressBooks: List<DavCollectionInfo>,
    /** The person's calendar addresses (lower-case email addresses), from the principal. */
    val userAddresses: List<String>,
)

/** One member of a collection as PROPFIND reports it. */
data class DavEntry(
    val path: String,
    val isCollection: Boolean,
    val etag: String?,
    val contentType: String?,
    val length: Long?,
    val modifiedAt: Instant?,
)

/** What changed in a collection since the last sync. */
data class DavChanges(val changed: List<String>, val removed: List<String>, val syncToken: String?, val etags: Map<String, String>)

/**
 * Thin, test-friendly layer over dav4jvm: discovery (RFC 6764 `.well-known`, principal, home-sets),
 * listing, `sync-collection` (RFC 6578) with an ETag fallback, bulk fetch and conditional writes.
 * All URLs are ones the user configured or the server returned for that user.
 */
class DavServer(private val config: DavConfig, engine: HttpClientEngine = CIO.create()) : AutoCloseable {
    val http: HttpClient = HttpClient(engine) {
        followRedirects = false // dav4jvm follows redirects itself, keeping authorization checks
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000 }
        install(Auth) {
            // credentials are only ever sent to the configured server's domain
            val hosts = listOfNotNull(config.caldavUrl, config.carddavUrl, config.webdavUrl).map { Url(it).host }.toSet()
            hosts.forEach { host ->
                providers += DomainAuthProvider(UrlUtils.hostToDomain(host), PreemptiveBasicDigestAuthProvider(config.username, config.password))
            }
        }
    }

    override fun close() = http.close()

    // ---- discovery -----------------------------------------------------------------------------

    suspend fun discover(): Discovery {
        var calendars = emptyList<DavCollectionInfo>()
        var books = emptyList<DavCollectionInfo>()
        var addresses = emptyList<String>()
        config.caldavUrl?.let { url ->
            val found = discoverCollections(Url(url), "caldav", CalDAV.CalendarHomeSet, CalDAV.Calendar) { it.supportsEvents() }
            calendars = found.first
            addresses = found.second
        }
        config.carddavUrl?.let { url ->
            books = discoverCollections(Url(url), "carddav", CardDAV.AddressbookHomeSet, CardDAV.Addressbook) { true }.first
        }
        return Discovery(calendars, books, (addresses + listOfNotNull(config.email?.lowercase())).distinct())
    }

    private fun Response.supportsEvents(): Boolean = this[SupportedCalendarComponentSet::class.java]?.supportsEvents ?: true

    private suspend fun discoverCollections(
        start: Url,
        wellKnown: String,
        homeSetName: Property.Name,
        kind: Property.Name,
        accept: (Response) -> Boolean,
    ): Pair<List<DavCollectionInfo>, List<String>> {
        val props = arrayOf(WebDAV.ResourceType, WebDAV.CurrentUserPrincipal, homeSetName, CalDAV.CalendarUserAddressSet)
        suspend fun probe(url: Url): Response? = try {
            propfind(url, 0, *props).firstOrNull()
        } catch (_: HttpException) {
            null
        }

        var self = probe(start)
        if (self == null || (!isCollectionOfKind(self, kind) && homeOf(self, homeSetName) == null && self[CurrentUserPrincipal::class.java]?.href == null)) {
            val wk = URLOrigin(start) + "/.well-known/$wellKnown"
            self = probe(Url(wk)) ?: self
        }
        self ?: return emptyList<DavCollectionInfo>() to emptyList()

        val addresses = userAddresses(self)
        if (isCollectionOfKind(self, kind)) return listOf(describe(self, start)) to addresses

        var homeUrl = homeOf(self, homeSetName)?.let { start.resolve(it) }
        var principalAddresses = addresses
        if (homeUrl == null) {
            val principal = self[CurrentUserPrincipal::class.java]?.href?.let { start.resolve(it) } ?: return emptyList<DavCollectionInfo>() to addresses
            val p = probe(principal) ?: return emptyList<DavCollectionInfo>() to addresses
            homeUrl = homeOf(p, homeSetName)?.let { principal.resolve(it) }
            principalAddresses = (addresses + userAddresses(p)).distinct()
        }
        homeUrl ?: return emptyList<DavCollectionInfo>() to principalAddresses
        val members = propfind(
            homeUrl, 1, WebDAV.ResourceType, WebDAV.DisplayName, CalDAV.SupportedCalendarComponentSet, WebDAV.SupportedReportSet,
        ).filter { isCollectionOfKind(it, kind) && accept(it) }
        return members.map { describe(it, homeUrl) } to principalAddresses
    }

    private fun URLOrigin(url: Url): String = "${url.protocol.name}://${url.host}:${url.port}"

    private fun isCollectionOfKind(r: Response, kind: Property.Name) = r[ResourceType::class.java]?.types?.contains(kind) == true

    private fun homeOf(r: Response, name: Property.Name): String? = when (name) {
        CalDAV.CalendarHomeSet -> r[CalendarHomeSet::class.java]?.hrefs?.firstOrNull()
        else -> r[AddressbookHomeSet::class.java]?.hrefs?.firstOrNull()
    }

    private fun userAddresses(r: Response): List<String> =
        r[CalendarUserAddressSet::class.java]?.hrefs.orEmpty()
            .filter { it.startsWith("mailto:", ignoreCase = true) }.map { it.substring(7).trim().lowercase() }

    private fun describe(r: Response, base: Url): DavCollectionInfo {
        val url = r.href
        val name = r[DisplayName::class.java]?.displayName?.takeIf(String::isNotBlank) ?: url.encodedPath.trimEnd('/').substringAfterLast('/')
        val sync = r[SupportedReportSet::class.java]?.reports?.contains(WebDAV.SyncCollection) == true
        return DavCollectionInfo(url, name, sync)
    }

    // ---- listing and change detection ----------------------------------------------------------

    private suspend fun propfind(url: Url, depth: Int, vararg props: Property.Name): List<Response> =
        DavResource(http, url).propfind(depth, *props).toList()
            .filterIsInstance<MultiStatusItem.Response>().map { it.response }.filter { it.isSuccess() }

    /** Members of the collection at [url] (not recursive). */
    suspend fun list(url: Url): List<DavEntry> {
        val self = url.withTrailingSlash()
        return propfind(url, 1, WebDAV.ResourceType, WebDAV.GetETag, WebDAV.GetContentType, WebDAV.GetContentLength, WebDAV.GetLastModified)
            .filter { it.href.encodedPath.trimEnd('/') != self.encodedPath.trimEnd('/') }
            .map { r ->
                DavEntry(
                    path = r.href.encodedPath,
                    isCollection = r[ResourceType::class.java]?.types?.contains(WebDAV.Collection) == true,
                    etag = r[GetETag::class.java]?.eTag,
                    contentType = r[GetContentType::class.java]?.type,
                    length = r[GetContentLength::class.java]?.contentLength,
                    modifiedAt = r[GetLastModified::class.java]?.lastModified?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) },
                )
            }
    }

    /**
     * Changes of a calendar or address book since [previous] (path to ETag) / [syncToken]. Uses
     * `sync-collection` when the server supports it and the token is still valid, otherwise lists
     * the collection and compares ETags.
     */
    suspend fun changes(collection: Url, previous: Map<String, String>, syncToken: String?, supportsSync: Boolean): DavChanges {
        if (supportsSync) {
            try {
                return viaSyncCollection(collection, previous, syncToken)
            } catch (e: HttpException) {
                // an expired token (403/409) or a server that only claims support: list instead
            }
        }
        val current = list(collection).filter { !it.isCollection }.associate { it.path to (it.etag ?: "") }
        val changed = current.filter { (path, tag) -> previous[path] != tag || tag.isEmpty() }.keys.toList()
        return DavChanges(changed, (previous.keys - current.keys).toList(), null, current)
    }

    private suspend fun viaSyncCollection(collection: Url, previous: Map<String, String>, syncToken: String?): DavChanges {
        val etags = previous.toMutableMap()
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var token: String? = null
        DavCollection(http, collection).reportChanges(syncToken, false, null, WebDAV.GetETag).toList().forEach { item ->
            when (item) {
                is MultiStatusItem.ExtraProperty -> (item.property as? SyncToken)?.let { token = it.token }
                is MultiStatusItem.Response -> {
                    val r = item.response
                    val path = r.href.encodedPath
                    if (path.trimEnd('/') == collection.encodedPath.trimEnd('/')) return@forEach
                    if (r.status?.value == 404) { removed += path; etags.remove(path) }
                    else if (r.isSuccess() && r[ResourceType::class.java]?.types?.contains(WebDAV.Collection) != true) {
                        changed += path
                        etags[path] = r[GetETag::class.java]?.eTag.orEmpty()
                    }
                }
            }
        }
        // the first report (no token) lists everything but cannot say what disappeared earlier: reconcile
        if (syncToken == null) removed += previous.keys - etags.keys.toSet()
        return DavChanges(changed.distinct(), removed.distinct(), token, etags)
    }

    // ---- reading and writing -------------------------------------------------------------------

    /** Calendar objects at [paths] (multiget), path to (ETag, iCalendar text). */
    suspend fun calendarObjects(calendar: Url, paths: List<String>): Map<String, Pair<String?, String>> {
        val out = mutableMapOf<String, Pair<String?, String>>()
        for (batch in paths.chunked(50)) {
            DavCalendar(http, calendar).multiget(batch.map { calendar.resolve(it)!! }).toList()
                .filterIsInstance<MultiStatusItem.Response>().map { it.response }.filter { it.isSuccess() }.forEach { r ->
                    r[CalendarData::class.java]?.iCalendar?.let { out[r.href.encodedPath] = r[GetETag::class.java]?.eTag to it }
                }
        }
        return out
    }

    /** Vcards at [paths] (multiget), path to (ETag, vCard text). */
    suspend fun cards(book: Url, paths: List<String>): Map<String, Pair<String?, String>> {
        val out = mutableMapOf<String, Pair<String?, String>>()
        for (batch in paths.chunked(50)) {
            DavAddressBook(http, book).multiget(batch.map { book.resolve(it)!! }).toList()
                .filterIsInstance<MultiStatusItem.Response>().map { it.response }.filter { it.isSuccess() }.forEach { r ->
                    r[AddressData::class.java]?.card?.let { out[r.href.encodedPath] = r[GetETag::class.java]?.eTag to it }
                }
        }
        return out
    }

    /** GET [url]; null when the body is larger than [limit]. Returns ETag and bytes. */
    suspend fun get(url: Url, limit: Long = MAX_EXTRACT_BYTES): Pair<String?, ByteArray>? =
        DavResource(http, url).get(Headers.build { append(HttpHeaders.Accept, "*/*") }) { response ->
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limit) null
            else response.readRawBytes().let { if (it.size > limit) null else response.headers[HttpHeaders.ETag] to it }
        }

    /**
     * PUT an iCalendar object. [ifMatch] makes it an update of exactly that version, [ifNoneMatch]
     * (`"*"`) a create that must not overwrite. Returns the new ETag, if the server sent one.
     */
    suspend fun putCalendar(url: Url, ics: String, ifMatch: String? = null, ifNoneMatch: String? = null): String? =
        DavResource(http, url).put(
            TextContent(ics, ContentType.parse("text/calendar; charset=utf-8")),
            Headers.build {
                ifMatch?.let { append(HttpHeaders.IfMatch, it) }
                ifNoneMatch?.let { append(HttpHeaders.IfNoneMatch, it) }
            },
        ) { it.headers[HttpHeaders.ETag] }

    /** DAV compliance classes the server announces for [url] (e.g. `calendar-auto-schedule`). */
    suspend fun capabilities(url: Url): Set<String> = DavResource(http, url).options().davCapabilities

    fun resolve(base: Url, path: String): Url = base.resolve(path) ?: Url(path)
}
