package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.report.ReportCitation
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportRevisionInfo
import net.robog.clarity.shared.report.ReportStyle
import net.robog.clarity.shared.report.ReportSummary
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.jsonb

/** Mirrors db/migration/V25__reports.sql. */
object Reports : Table("report") {
    val id = uuid("id")
    val kind = text("kind")
    val title = text("title")
    val createdAt = timestamp("created_at")
    val createdByRun = text("created_by_run").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ReportRevisions : Table("report_revision") {
    val id = long("id").autoIncrement()
    val reportId = uuid("report_id")
    val n = integer("n")
    val markdown = text("source")
    val style = jsonb<ReportStyle>("style", ClarityJson)
    val createdAt = timestamp("created_at")
    val createdByRun = text("created_by_run").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ReportRenditions : Table("report_rendition") {
    val revisionId = long("revision_id")
    val format = text("format")
    val rendererVersion = text("renderer_version")
    val blobPath = text("blob_path")
    val sizeBytes = long("size_bytes")
    val contentSha256 = text("content_sha256")
    val createdAt = timestamp("created_at")
    val lastAccessedAt = timestamp("last_accessed_at")
    override val primaryKey = PrimaryKey(revisionId, format, rendererVersion)
}

object ReportSources : Table("report_source") {
    val id = long("id").autoIncrement()
    val reportId = uuid("report_id")
    val url = text("url")
    val title = text("title").nullable()
    val citedText = text("cited_text").nullable()
    val fetchedAt = timestamp("fetched_at")
    override val primaryKey = PrimaryKey(id)
}

data class StoredReport(val id: String, val kind: ReportKind, val title: String, val createdAt: Instant, val createdByRun: String?)

data class StoredRevision(
    /** The row id renditions point at. */
    val id: Long,
    val reportId: String,
    val n: Int,
    val source: String,
    val style: ReportStyle,
    val createdAt: Instant,
    val createdByRun: String?,
)

data class StoredRendition(
    val revisionId: Long,
    val format: ReportFormat,
    val rendererVersion: String,
    /** Relative to the reports directory ([ReportBlobs]). */
    val blobPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
    val lastAccessedAt: Instant,
)

/** The new source and style of a revision, made from the latest one. */
data class RevisionChange(val source: String, val style: ReportStyle)

/** Reports, their revisions and sources, and the rendition cache's rows. Revisions are append-only. */
interface ReportStore {
    /** A new report with revision 1. */
    suspend fun create(kind: ReportKind, title: String, source: String, style: ReportStyle, run: String?): Pair<StoredReport, StoredRevision>

    suspend fun report(id: String): StoredReport?

    /** Revision [n] of [id], or its latest when [n] is null. */
    suspend fun revision(id: String, n: Int? = null): StoredRevision?

    /** Every revision's metadata, oldest first. */
    suspend fun revisions(id: String): List<ReportRevisionInfo>

    /**
     * Appends revision n+1 made by [change] from the latest revision, with the report row locked, so
     * two edits never both build on the same revision. Left: [change]'s refusal, or "no such report".
     */
    suspend fun addRevision(id: String, run: String?, change: (StoredRevision) -> Either<String, RevisionChange>): Either<String, StoredRevision>

    /** Newest first (by latest revision). */
    suspend fun list(limit: Int = 100): List<ReportSummary>

    /** Adds [citations] to [id]'s sources (a URL already there is kept as it was); returns how many were new. */
    suspend fun addSources(id: String, citations: List<UrlCitation>, at: Instant): Int

    suspend fun sources(id: String): List<ReportCitation>

    suspend fun rendition(revisionId: Long, format: ReportFormat, rendererVersion: String): StoredRendition?

    /** The newest rendition of any renderer version (when the renderer is down). */
    suspend fun newestRendition(revisionId: Long, format: ReportFormat): StoredRendition?

    /** Records a rendition; false when one with the same key exists (a concurrent render won). */
    suspend fun saveRendition(rendition: StoredRendition): Boolean

    suspend fun touchRendition(revisionId: Long, format: ReportFormat, rendererVersion: String, at: Instant)

    /** Every rendition, least recently accessed first (the sweeper's order). */
    suspend fun renditionsByAccess(): List<StoredRendition>

    suspend fun deleteRendition(revisionId: Long, format: ReportFormat, rendererVersion: String)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedReportStore(private val database: Database, private val clock: Clock) : ReportStore {
    override suspend fun create(kind: ReportKind, title: String, source: String, style: ReportStyle, run: String?): Pair<StoredReport, StoredRevision> {
        val id = Uuid.random()
        val now = clock.now()
        return database.tx {
            Reports.insert {
                it[Reports.id] = id
                it[Reports.kind] = kind.wireName
                it[Reports.title] = title
                it[createdAt] = now
                it[createdByRun] = run
            }
            ReportRevisions.insert {
                it[reportId] = id
                it[n] = 1
                it[ReportRevisions.markdown] = source
                it[ReportRevisions.style] = style
                it[createdAt] = now
                it[createdByRun] = run
            }
            val report = Reports.selectAll().where { Reports.id eq id }.single().toReport()
            report to latestRow(id)!!.toRevision()
        }
    }

    override suspend fun report(id: String): StoredReport? {
        val uuid = id.uuidOrNull() ?: return null
        return database.tx { Reports.selectAll().where { Reports.id eq uuid }.singleOrNull()?.toReport() }
    }

    override suspend fun revision(id: String, n: Int?): StoredRevision? {
        val uuid = id.uuidOrNull() ?: return null
        return database.tx {
            if (n == null) latestRow(uuid)?.toRevision()
            else ReportRevisions.selectAll().where { (ReportRevisions.reportId eq uuid) and (ReportRevisions.n eq n) }.singleOrNull()?.toRevision()
        }
    }

    override suspend fun revisions(id: String): List<ReportRevisionInfo> {
        val uuid = id.uuidOrNull() ?: return emptyList()
        return database.tx {
            ReportRevisions.selectAll().where { ReportRevisions.reportId eq uuid }.orderBy(ReportRevisions.n).map {
                ReportRevisionInfo(it[ReportRevisions.n], it[ReportRevisions.createdAt], it[ReportRevisions.style], it[ReportRevisions.markdown].length)
            }
        }
    }

    override suspend fun addRevision(id: String, run: String?, change: (StoredRevision) -> Either<String, RevisionChange>): Either<String, StoredRevision> {
        val uuid = id.uuidOrNull() ?: return "No report with id $id.".left()
        val now = clock.now()
        return database.tx {
            // The report row is the lock: concurrent edits of one report go one after the other.
            Reports.selectAll().where { Reports.id eq uuid }.forUpdate().singleOrNull() ?: return@tx "No report with id $id.".left()
            val latest = latestRow(uuid)?.toRevision() ?: return@tx "Report $id has no revisions.".left()
            change(latest).map { next ->
                ReportRevisions.insert {
                    it[reportId] = uuid
                    it[n] = latest.n + 1
                    it[markdown] = next.source
                    it[style] = next.style
                    it[createdAt] = now
                    it[createdByRun] = run
                }
                latestRow(uuid)!!.toRevision()
            }
        }
    }

    override suspend fun list(limit: Int): List<ReportSummary> = database.tx {
        val reports = Reports.selectAll().orderBy(Reports.createdAt, SortOrder.DESC).limit(limit).map { it.toReport() }
        if (reports.isEmpty()) return@tx emptyList()
        val latest = HashMap<String, Pair<Int, Instant>>()
        ReportRevisions.select(ReportRevisions.reportId, ReportRevisions.n, ReportRevisions.createdAt)
            .where { ReportRevisions.reportId inList reports.map { Uuid.parse(it.id) } }
            .forEach { row ->
                val key = row[ReportRevisions.reportId].toString()
                val n = row[ReportRevisions.n]
                if ((latest[key]?.first ?: 0) < n) latest[key] = n to row[ReportRevisions.createdAt]
            }
        reports.map { r ->
            val (n, at) = latest[r.id] ?: (1 to r.createdAt)
            ReportSummary(r.id, r.kind, r.title, r.createdAt, n, at)
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun addSources(id: String, citations: List<UrlCitation>, at: Instant): Int {
        val uuid = id.uuidOrNull() ?: return 0
        return database.tx {
            citations.distinctBy { it.url }.count { c ->
                ReportSources.insertIgnore {
                    it[reportId] = uuid
                    it[url] = c.url
                    it[title] = c.title?.take(MAX_SOURCE_TITLE)
                    it[citedText] = c.content?.take(MAX_CITED_TEXT)
                    it[fetchedAt] = at
                }.insertedCount > 0
            }
        }
    }

    override suspend fun sources(id: String): List<ReportCitation> {
        val uuid = id.uuidOrNull() ?: return emptyList()
        return database.tx {
            ReportSources.selectAll().where { ReportSources.reportId eq uuid }.orderBy(ReportSources.id).map {
                ReportCitation(it[ReportSources.url], it[ReportSources.title], it[ReportSources.citedText], it[ReportSources.fetchedAt])
            }
        }
    }

    override suspend fun rendition(revisionId: Long, format: ReportFormat, rendererVersion: String): StoredRendition? = database.tx {
        ReportRenditions.selectAll().where {
            (ReportRenditions.revisionId eq revisionId) and (ReportRenditions.format eq format.wireName) and (ReportRenditions.rendererVersion eq rendererVersion)
        }.singleOrNull()?.toRendition()
    }

    override suspend fun newestRendition(revisionId: Long, format: ReportFormat): StoredRendition? = database.tx {
        ReportRenditions.selectAll().where { (ReportRenditions.revisionId eq revisionId) and (ReportRenditions.format eq format.wireName) }
            .orderBy(ReportRenditions.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.toRendition()
    }

    override suspend fun saveRendition(rendition: StoredRendition): Boolean = database.tx {
        ReportRenditions.insertIgnore {
            it[revisionId] = rendition.revisionId
            it[format] = rendition.format.wireName
            it[rendererVersion] = rendition.rendererVersion
            it[blobPath] = rendition.blobPath
            it[sizeBytes] = rendition.sizeBytes
            it[contentSha256] = rendition.sha256
            it[createdAt] = rendition.createdAt
            it[lastAccessedAt] = rendition.lastAccessedAt
        }.insertedCount > 0
    }

    override suspend fun touchRendition(revisionId: Long, format: ReportFormat, rendererVersion: String, at: Instant) {
        database.tx {
            ReportRenditions.update({
                (ReportRenditions.revisionId eq revisionId) and (ReportRenditions.format eq format.wireName) and (ReportRenditions.rendererVersion eq rendererVersion)
            }) { it[lastAccessedAt] = at }
        }
    }

    override suspend fun renditionsByAccess(): List<StoredRendition> = database.tx {
        ReportRenditions.selectAll().orderBy(ReportRenditions.lastAccessedAt).mapNotNull { it.toRenditionOrNull() }
    }

    override suspend fun deleteRendition(revisionId: Long, format: ReportFormat, rendererVersion: String) {
        database.tx {
            ReportRenditions.deleteWhere {
                (ReportRenditions.revisionId eq revisionId) and (ReportRenditions.format eq format.wireName) and (ReportRenditions.rendererVersion eq rendererVersion)
            }
        }
    }

    private fun latestRow(id: Uuid): ResultRow? =
        ReportRevisions.selectAll().where { ReportRevisions.reportId eq id }.orderBy(ReportRevisions.n, SortOrder.DESC).limit(1).singleOrNull()

    private fun ResultRow.toReport() = StoredReport(
        id = this[Reports.id].toString(),
        kind = ReportKind.fromWire(this[Reports.kind]) ?: ReportKind.DOCUMENT,
        title = this[Reports.title],
        createdAt = this[Reports.createdAt],
        createdByRun = this[Reports.createdByRun],
    )

    private fun ResultRow.toRevision() = StoredRevision(
        id = this[ReportRevisions.id],
        reportId = this[ReportRevisions.reportId].toString(),
        n = this[ReportRevisions.n],
        source = this[ReportRevisions.markdown],
        style = this[ReportRevisions.style],
        createdAt = this[ReportRevisions.createdAt],
        createdByRun = this[ReportRevisions.createdByRun],
    )

    private fun ResultRow.toRenditionOrNull(): StoredRendition? {
        val format = ReportFormat.fromWire(this[ReportRenditions.format]) ?: return null
        return StoredRendition(
            revisionId = this[ReportRenditions.revisionId],
            format = format,
            rendererVersion = this[ReportRenditions.rendererVersion],
            blobPath = this[ReportRenditions.blobPath],
            sizeBytes = this[ReportRenditions.sizeBytes],
            sha256 = this[ReportRenditions.contentSha256],
            createdAt = this[ReportRenditions.createdAt],
            lastAccessedAt = this[ReportRenditions.lastAccessedAt],
        )
    }

    private fun ResultRow.toRendition(): StoredRendition? = toRenditionOrNull()

    private companion object {
        const val MAX_SOURCE_TITLE = 500
        const val MAX_CITED_TEXT = 4_000
    }
}

private fun String.uuidOrNull(): Uuid? = runCatching { Uuid.parse(trim()) }.getOrNull()
