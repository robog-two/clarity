package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportFormatOption
import net.robog.clarity.shared.report.ReportKind

private val log = KotlinLogging.logger {}

/** Why a download did not work. */
sealed interface DownloadError {
    val message: String

    data class NotFound(override val message: String) : DownloadError

    /** The format does not apply to this kind of report. */
    data class Unsupported(override val message: String) : DownloadError

    data class Render(val error: RenderError) : DownloadError {
        override val message: String get() = error.message
    }
}

/** A file to send: its bytes, name and media type. */
class Download(val bytes: ByteArray, val filename: String, val format: ReportFormat)

/** Pure: the formats a report of [kind] downloads as, with their labels ("Word / Pages" for docx). */
fun formatsFor(kind: ReportKind): List<ReportFormat> = when (kind) {
    ReportKind.DOCUMENT -> ReportFormat.DOCUMENT
    else -> emptyList()
}

fun formatOptions(kind: ReportKind): List<ReportFormatOption> = formatsFor(kind).map { ReportFormatOption(it.wireName, it.label) }

/**
 * Reports for the API and the agent's tools: details, sources, and downloads. A download renders on
 * demand and caches the file ([ReportStore] `report_rendition` + [ReportBlobs]) under (revision,
 * format, renderer version); Markdown is the source itself and is never cached. When the renderer
 * cannot be reached, the newest file cached for the revision and format (any renderer version) is
 * served instead.
 */
@Inject
@SingleIn(AppScope::class)
class ReportService(
    private val store: ReportStore,
    private val renderer: Renderer,
    private val blobs: ReportBlobs,
    private val clock: Clock,
) {
    suspend fun detail(id: String): ReportDetail? {
        val report = store.report(id) ?: return null
        return ReportDetail(
            id = report.id,
            kind = report.kind,
            title = report.title,
            createdAt = report.createdAt,
            revisions = store.revisions(id),
            sources = store.sources(id),
            formats = formatOptions(report.kind),
        )
    }

    /** The Markdown of revision [rev] (latest when null). */
    suspend fun source(id: String, rev: Int?): StoredRevision? = store.revision(id, rev)

    suspend fun download(id: String, rev: Int?, format: ReportFormat): Either<DownloadError, Download> {
        val report = store.report(id) ?: return DownloadError.NotFound("No report with id $id.").left()
        if (format !in formatsFor(report.kind)) return DownloadError.Unsupported("A ${report.kind.wireName} report does not download as ${format.wireName}.").left()
        val revision = store.revision(id, rev) ?: return DownloadError.NotFound("Report $id has no revision $rev.").left()
        val latest = rev == null || store.revision(id)?.n == revision.n
        val filename = downloadFilename(report.title, revision.n, latest, format)
        if (format == ReportFormat.MD) return Download(revision.source.encodeToByteArray(), filename, format).right()
        return rendered(revision, format).map { Download(it, filename, format) }
    }

    private suspend fun rendered(revision: StoredRevision, format: ReportFormat): Either<DownloadError, ByteArray> {
        val version = renderer.version()
        val cached = if (version != null) store.rendition(revision.id, format, version) else store.newestRendition(revision.id, format)
        if (cached != null) {
            val bytes = blobs.read(cached.blobPath)
            if (bytes != null) {
                store.touchRendition(cached.revisionId, cached.format, cached.rendererVersion, clock.now())
                return bytes.right()
            }
            // The row outlived its file (a restore without the cache, a manual clean-up): drop it and render again.
            store.deleteRendition(cached.revisionId, cached.format, cached.rendererVersion)
        }
        val rendered = renderer.render(revision.source, revision.style, format).fold({ return DownloadError.Render(it).left() }, { it })
        cache(revision, format, rendered)
        return rendered.bytes.right()
    }

    /** Stores a fresh rendition; a failure to cache never fails the download. */
    private suspend fun cache(revision: StoredRevision, format: ReportFormat, rendered: Rendered) {
        try {
            val relative = blobs.relativePath(revision.id, format, rendered.rendererVersion)
            val sha = blobs.write(relative, rendered.bytes)
            val now = clock.now()
            store.saveRendition(
                StoredRendition(revision.id, format, rendered.rendererVersion, relative, rendered.bytes.size.toLong(), sha, now, now),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "rendition of revision ${revision.id} as ${format.wireName} not cached: ${e.message}" }
        }
    }
}

/**
 * Drops rendition files and rows, least recently downloaded first, until the cache is at most
 * [maxBytes]; renditions made by another renderer version than [currentVersion] go first, since they
 * would never be served again while the renderer is up. Revisions are never touched. Returns how many
 * were dropped.
 */
suspend fun sweepRenditions(store: ReportStore, blobs: ReportBlobs, maxBytes: Long, currentVersion: String?): Int {
    val all = store.renditionsByAccess()
    val (stale, current) = if (currentVersion == null) emptyList<StoredRendition>() to all else all.partition { it.rendererVersion != currentVersion }
    val victims = renditionsToDrop(stale, current, maxBytes)
    victims.forEach { r ->
        blobs.delete(r.blobPath)
        store.deleteRendition(r.revisionId, r.format, r.rendererVersion)
    }
    return victims.size
}

/**
 * Pure: which renditions to drop: every [stale] one, then the least recently accessed of [current]
 * (already in that order) until what stays fits in [maxBytes].
 */
fun renditionsToDrop(stale: List<StoredRendition>, current: List<StoredRendition>, maxBytes: Long): List<StoredRendition> {
    var total = current.sumOf { it.sizeBytes }
    val drop = stale.toMutableList()
    for (r in current) {
        if (total <= maxBytes) break
        drop += r
        total -= r.sizeBytes
    }
    return drop
}
