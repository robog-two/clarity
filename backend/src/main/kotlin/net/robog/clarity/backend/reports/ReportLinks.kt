package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.homeZone
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportLink
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Mirrors db/migration/V26__report_link.sql. */
object ReportLinks : Table("report_link") {
    val lookup = text("lookup")
    val reportId = uuid("report_id")
    val secret = binary("secret")
    val createdAt = timestamp("created_at")
    val downloads = long("downloads").default(0)
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(lookup)
}

/** Bytes of a token's lookup part (12 base64url characters) and of its secret (24 characters, 144 bits). */
const val LINK_LOOKUP_BYTES = 9
const val LINK_SECRET_BYTES = 18
private const val LOOKUP_CHARS = LINK_LOOKUP_BYTES / 3 * 4
private const val TOKEN_CHARS = (LINK_LOOKUP_BYTES + LINK_SECRET_BYTES) / 3 * 4
private val TOKEN = Regex("^[A-Za-z0-9_-]{$TOKEN_CHARS}$")
private val base64 = Base64.getUrlEncoder().withoutPadding()

/** Pure: the token for a link's [lookup] and [secret]. */
fun linkToken(lookup: String, secret: ByteArray): String = lookup + base64.encodeToString(secret)

/** Pure: a token's (lookup, secret), or null when it is not one (wrong length or characters). */
fun splitLinkToken(token: String): Pair<String, ByteArray>? {
    if (!TOKEN.matches(token)) return null
    val secret = runCatching { Base64.getUrlDecoder().decode(token.substring(LOOKUP_CHARS)) }.getOrNull() ?: return null
    return token.substring(0, LOOKUP_CHARS) to secret
}

/** Pure: a link's public path. */
fun linkPath(token: String): String = "${ApiPaths.GOODS_PUBLIC}/$token"

/**
 * The secret links of Goods. A link is created on the first share or download, returned again while
 * it is live, and revoked on request; a revoked token never resolves again. Tokens come from a
 * [SecureRandom]; the secret part is compared in constant time.
 */
interface ReportLinkStore {
    /** The live link, null when there is none. */
    suspend fun live(reportId: String): LiveLink?

    /** The live link, made now when there is none; null when there is no such report. */
    suspend fun ensure(reportId: String): LiveLink?

    /** Turns the live link off; false when there was none. */
    suspend fun revoke(reportId: String): Boolean

    /** The report a live [token] opens, or null. */
    suspend fun resolve(token: String): String?

    /** Counts one file downloaded through [token] (only while it is live). */
    suspend fun countDownload(token: String)
}

/** A live link: its token and how many files were downloaded through it. */
data class LiveLink(val token: String, val downloads: Long)

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedReportLinkStore(private val database: Database, private val clock: Clock) : ReportLinkStore {
    private val random = SecureRandom()

    override suspend fun live(reportId: String): LiveLink? {
        val uuid = reportId.uuidOrNull() ?: return null
        return database.tx { liveRow(uuid) }
    }

    override suspend fun ensure(reportId: String): LiveLink? {
        val uuid = reportId.uuidOrNull() ?: return null
        return database.tx {
            // The report row is the lock: two first shares at once make one link.
            Reports.selectAll().where { Reports.id eq uuid }.forUpdate().singleOrNull() ?: return@tx null
            liveRow(uuid) ?: run {
                val lookup = base64.encodeToString(ByteArray(LINK_LOOKUP_BYTES).also(random::nextBytes))
                val secret = ByteArray(LINK_SECRET_BYTES).also(random::nextBytes)
                ReportLinks.insert {
                    it[ReportLinks.lookup] = lookup
                    it[ReportLinks.reportId] = uuid
                    it[ReportLinks.secret] = secret
                    it[createdAt] = clock.now()
                }
                LiveLink(linkToken(lookup, secret), 0)
            }
        }
    }

    override suspend fun revoke(reportId: String): Boolean {
        val uuid = reportId.uuidOrNull() ?: return false
        return database.tx {
            ReportLinks.update({ (ReportLinks.reportId eq uuid) and ReportLinks.revokedAt.isNull() }) { it[revokedAt] = clock.now() } > 0
        }
    }

    override suspend fun resolve(token: String): String? {
        val (lookup, secret) = splitLinkToken(token) ?: return null
        val row = database.tx {
            ReportLinks.selectAll().where { (ReportLinks.lookup eq lookup) and ReportLinks.revokedAt.isNull() }.singleOrNull()
        } ?: return null
        return if (MessageDigest.isEqual(row[ReportLinks.secret], secret)) row[ReportLinks.reportId].toString() else null
    }

    override suspend fun countDownload(token: String) {
        val (lookup, secret) = splitLinkToken(token) ?: return
        database.tx {
            // One atomic increment; the secret is checked first, in constant time, like resolve.
            val row = ReportLinks.selectAll().where { (ReportLinks.lookup eq lookup) and ReportLinks.revokedAt.isNull() }.singleOrNull()
            if (row != null && MessageDigest.isEqual(row[ReportLinks.secret], secret)) {
                ReportLinks.update({ (ReportLinks.lookup eq lookup) and ReportLinks.revokedAt.isNull() }) {
                    it[downloads] = downloads + 1L
                }
            }
        }
    }

    private fun liveRow(uuid: Uuid): LiveLink? =
        ReportLinks.selectAll().where { (ReportLinks.reportId eq uuid) and ReportLinks.revokedAt.isNull() }.singleOrNull()
            ?.let { LiveLink(linkToken(it[ReportLinks.lookup], it[ReportLinks.secret]), it[ReportLinks.downloads]) }
}

private fun String.uuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()

/** The headers of every public Goods response: not indexed, no referrer, nothing kept by caches. */
private fun ApplicationCall.publicHeaders(cache: CacheControl = CacheControl.NoStore(CacheControl.Visibility.Private)) {
    response.cacheControl(cache)
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Robots-Tag", "noindex, nofollow, noarchive")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )
}

/** The page's title font (Libre Caslon Text Italic, OFL), served here so the page loads nothing from elsewhere. */
const val GOODS_FONT_PATH = "${ApiPaths.GOODS_PUBLIC}/font/libre-caslon-text-italic.ttf"

/**
 * A Good's secret link. Device-authenticated: `POST /api/v1/reports/{id}/link` (the live link, made
 * on first use) and `DELETE …/link` (turn it off). Public, the only unauthenticated report routes:
 * `GET /g/{token}` (a small download page) and `GET /g/{token}/{format}` (the latest revision's file),
 * plus the page's font. A token gives exactly its own report; unknown or revoked ones get a plain 404.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class ReportLinkRoutes(
    private val reports: ReportService,
    private val store: ReportStore,
    private val links: ReportLinkStore,
    private val settings: SettingsProvider,
) : HttpModule {
    private val font: ByteArray? by lazy { javaClass.getResourceAsStream("/goods/libre-caslon-text-italic.ttf")?.use { it.readBytes() } }

    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                post("${ApiPaths.REPORTS}/{id}/link") {
                    val link = links.ensure(call.parameters["id"].orEmpty())
                    if (link == null) call.respondProblem(EdgeProblem.NotFound("No such report."))
                    else call.respond(ReportLink(linkPath(link.token), link.downloads))
                }
                delete("${ApiPaths.REPORTS}/{id}/link") {
                    links.revoke(call.parameters["id"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            get(GOODS_FONT_PATH) {
                val bytes = font ?: return@get call.respond(HttpStatusCode.NotFound)
                call.publicHeaders(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
                call.respondBytes(bytes, ContentType("font", "ttf"))
            }
            get("${ApiPaths.GOODS_PUBLIC}/{token}") {
                val token = call.parameters["token"].orEmpty()
                val id = links.resolve(token) ?: return@get call.notFoundPage()
                val report = store.report(id) ?: return@get call.notFoundPage()
                val latest = store.revision(id) ?: return@get call.notFoundPage()
                val formats = formatsFor(report.kind).map { PageFormat(it.label, "${linkPath(token)}/${it.wireName}", ".${it.extension}") }
                call.publicHeaders()
                call.respondText(goodsPage(report.title, updatedLine(latest.createdAt, settings.homeZone()), formats), ContentType.Text.Html.withParameter("charset", "utf-8"))
            }
            get("${ApiPaths.GOODS_PUBLIC}/{token}/{format}") {
                val token = call.parameters["token"].orEmpty()
                val id = links.resolve(token) ?: return@get call.notFoundPage()
                val format = ReportFormat.fromWire(call.parameters["format"].orEmpty()) ?: return@get call.notFoundPage()
                reports.download(id, null, format).fold(
                    { error ->
                        if (error is DownloadError.Render) call.respondProblem(error.toProblem()) else call.notFoundPage()
                    },
                    { file ->
                        // Counted once the file is in hand: a failed render or an unknown format is not a download.
                        links.countDownload(token)
                        call.publicHeaders()
                        call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file.filename))
                        call.respondBytes(file.bytes, ContentType.parse(file.format.mimeType))
                    },
                )
            }
        }
    }

    private suspend fun ApplicationCall.notFoundPage() {
        publicHeaders()
        respondText(goodsNotFoundPage(), ContentType.Text.Html.withParameter("charset", "utf-8"), HttpStatusCode.NotFound)
    }
}

/** One download on the page: its label ("Word / Pages"), its link, its file type (".docx"). */
data class PageFormat(val label: String, val href: String, val extension: String)

/** Pure: "Updated October 28, 2026" in the person's zone. */
fun updatedLine(at: Instant, zone: TimeZone): String {
    val date = at.toLocalDateTime(zone).date
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "Updated $month ${date.day}, ${date.year}"
}

/** Pure: [text] safe inside HTML text and double-quoted attributes. */
fun html(text: String): String = buildString {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

private val PAGE_STYLE = """
@font-face{font-family:"Libre Caslon Text";font-style:italic;font-weight:400 700;font-display:swap;src:url("$GOODS_FONT_PATH") format("truetype")}
:root{--bg:#F6F2EC;--fg:#343434;--muted:#5E5B56;--row:#ECE7DF;--row-on:#E0D9CF;--accent:#8A4C14;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root{--bg:#343434;--fg:#ECE7DF;--muted:#C4BFB7;--row:#3F3F3F;--row-on:#505050;--accent:#FFBE85}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",sans-serif;-webkit-text-size-adjust:100%}
main{max-width:34rem;margin:0 auto;padding:48px 20px 40px}
.kicker{margin:0 0 12px;font-size:12px;font-weight:500;letter-spacing:.03em;color:var(--muted)}
h1{margin:0 0 8px;font-family:"Libre Caslon Text",Georgia,"Times New Roman",serif;font-style:italic;font-weight:600;font-size:32px;line-height:1.2;overflow-wrap:anywhere}
.date{margin:0 0 28px;font-size:14px;color:var(--muted)}
ul{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:2px}
a{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:56px;padding:14px 20px;background:var(--row);color:var(--fg);text-decoration:none;border-radius:6px}
li:first-child a{border-top-left-radius:24px;border-top-right-radius:24px}
li:last-child a{border-bottom-left-radius:24px;border-bottom-right-radius:24px}
a:hover,a:active{background:var(--row-on)}
a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.label{font-weight:500}
.ext{font-size:14px;color:var(--muted);white-space:nowrap}
.ext::after{content:" \2193";color:var(--accent)}
footer{margin-top:28px;font-size:13px;color:var(--muted)}
""".trim()

private fun pageShell(title: String, body: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex, nofollow"><meta name="referrer" content="no-referrer"><meta name="color-scheme" content="light dark">
<title>${html(title)}</title><style>$PAGE_STYLE</style></head>
<body><main>$body</main></body></html>
"""

/** Pure: a Good's download page: what it is, when it changed, and a link per format. Nothing else of the person's. */
fun goodsPage(title: String, updated: String, formats: List<PageFormat>): String = pageShell(
    title,
    """<p class="kicker">A document from Clarity</p><h1>${html(title)}</h1><p class="date">${html(updated)}</p>""" +
        "<ul>" + formats.joinToString("") { f ->
            """<li><a href="${html(f.href)}" download><span class="label">${html(f.label)}</span><span class="ext">${html(f.extension)}</span></a></li>"""
        } + "</ul>" +
        "<footer>Anyone with this link can download these files.</footer>",
)

/** Pure: the page for an unknown or turned-off link. */
fun goodsNotFoundPage(): String = pageShell(
    "Not found",
    """<p class="kicker">Clarity</p><h1>This link isn’t available</h1><p class="date">It may have been turned off. Ask whoever sent it for a new one.</p>""",
)
