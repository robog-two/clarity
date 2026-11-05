package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Mirrors db/migration/V45__report_image.sql. */
object ReportImageRows : Table("report_image") {
    val reportId = uuid("report_id")
    val key = text("key")
    val url = text("url")
    val contentType = text("content_type").nullable()
    val bytes = binary("bytes").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val error = text("error").nullable()
    val fetchedAt = timestamp("fetched_at")
    override val primaryKey = PrimaryKey(reportId, key)
}

/** A stored image row: [bytes] is null for a failed fetch (then [error] says why). */
class StoredImage(
    val key: String,
    val url: String,
    val contentType: String?,
    val bytes: ByteArray?,
    val width: Int?,
    val height: Int?,
    val error: String?,
)

interface ReportImageStore {
    /** The row for [key] of [reportId] (a failed fetch included), or null when none was recorded. */
    suspend fun find(reportId: String, key: String): StoredImage?

    /** The rows for [keys] (failures included). */
    suspend fun rows(reportId: String, keys: Collection<String>): List<StoredImage>

    /** Records [image]; false when the report already had that key (the first one stays). */
    suspend fun save(reportId: String, image: StoredImage, at: Instant): Boolean
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedReportImageStore(private val database: Database) : ReportImageStore {
    override suspend fun find(reportId: String, key: String): StoredImage? {
        val id = runCatching { Uuid.parse(reportId.trim()) }.getOrNull() ?: return null
        return database.tx {
            ReportImageRows.selectAll().where { (ReportImageRows.reportId eq id) and (ReportImageRows.key eq key) }.singleOrNull()?.toImage()
        }
    }

    override suspend fun rows(reportId: String, keys: Collection<String>): List<StoredImage> {
        val id = runCatching { Uuid.parse(reportId.trim()) }.getOrNull() ?: return emptyList()
        if (keys.isEmpty()) return emptyList()
        return database.tx {
            ReportImageRows.selectAll().where { (ReportImageRows.reportId eq id) and (ReportImageRows.key inList keys.toList()) }.map { it.toImage() }
        }
    }

    override suspend fun save(reportId: String, image: StoredImage, at: Instant): Boolean {
        val id = runCatching { Uuid.parse(reportId.trim()) }.getOrNull() ?: return false
        return database.tx {
            ReportImageRows.insertIgnore {
                it[ReportImageRows.reportId] = id
                it[key] = image.key
                it[url] = image.url
                it[contentType] = image.contentType
                it[bytes] = image.bytes
                it[width] = image.width
                it[height] = image.height
                it[error] = image.error
                it[fetchedAt] = at
            }.insertedCount > 0
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toImage() = StoredImage(
        key = this[ReportImageRows.key],
        url = this[ReportImageRows.url],
        contentType = this[ReportImageRows.contentType],
        bytes = this[ReportImageRows.bytes],
        width = this[ReportImageRows.width],
        height = this[ReportImageRows.height],
        error = this[ReportImageRows.error],
    )
}

/** An image for the renderer. */
class RenderImage(val contentType: String, val bytes: ByteArray, val width: Int?, val height: Int?)

/**
 * What a render gets for a revision: [images] by key (the ones fetched), and whether any image the
 * Markdown names is still [pending] (no row yet), in which case the file must not be cached.
 */
class RenderImages(val images: Map<String, RenderImage>, val pending: Boolean) {
    companion object {
        val NONE = RenderImages(emptyMap(), pending = false)
    }
}

/**
 * The images of reports: fetching what a saved revision's Markdown names (in the background, so the
 * tool call never waits), and serving them to the route and the renderer. Fetches go through
 * [ImageFetcher] (the egress proxy); an image already recorded for the report (kept or failed) is not
 * fetched again.
 */
@Inject
@SingleIn(AppScope::class)
class ReportImages(
    private val store: ReportImageStore,
    private val fetcher: ImageFetcher,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Starts fetching the images [markdown] names that [reportId] does not have yet. Returns at once. */
    fun schedule(reportId: String, markdown: String) {
        if (imagesToFetch(markdown).isEmpty()) return
        scope.launch {
            try {
                fetchMissing(reportId, markdown)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logFetchFailure("report $reportId", "fetch job failed: ${e::class.simpleName}")
            }
        }
    }

    /** Fetches and records the images [markdown] names that have no row yet; one at a time. */
    suspend fun fetchMissing(reportId: String, markdown: String) {
        val wanted = imagesToFetch(markdown).associateBy(::imageKey)
        if (wanted.isEmpty()) return
        val have = store.rows(reportId, wanted.keys).map { it.key }.toSet()
        for ((key, url) in wanted) {
            if (key in have || !inFlight.add("$reportId/$key")) continue
            try {
                val image = when (val outcome = fetcher.fetch(url)) {
                    is ImageOutcome.Fetched -> outcome.image.let { StoredImage(key, url, it.contentType, it.bytes, it.width, it.height, null) }
                    is ImageOutcome.Failed -> {
                        logFetchFailure(url, outcome.reason)
                        StoredImage(key, url, null, null, null, null, outcome.reason.take(300))
                    }
                }
                store.save(reportId, image, clock.now())
            } finally {
                inFlight.remove("$reportId/$key")
            }
        }
    }

    /** The stored image for [key], only when it was fetched (a failed fetch is none). */
    suspend fun image(reportId: String, key: String): StoredImage? = store.find(reportId, key)?.takeIf { it.bytes != null }

    /** The images a render of [markdown] gets, and whether some are still to come (then they are fetched). */
    suspend fun forRender(reportId: String, markdown: String): RenderImages {
        val wanted = imagesToFetch(markdown).map(::imageKey)
        if (wanted.isEmpty()) return RenderImages.NONE
        val rows = store.rows(reportId, wanted).associateBy { it.key }
        val pending = wanted.any { it !in rows }
        if (pending) schedule(reportId, markdown)
        // In the order the Markdown names them.
        val images = wanted.mapNotNull { key ->
            rows[key]?.let { r -> r.bytes?.let { key to RenderImage(r.contentType!!, it, r.width, r.height) } }
        }.toMap()
        return RenderImages(images, pending)
    }
}
