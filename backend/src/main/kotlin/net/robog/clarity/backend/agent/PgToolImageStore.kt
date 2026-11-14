package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ToolImage
import net.robog.clarity.harness.ToolImageStore
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** Mirrors `tool_image` (V49). */
object ToolImages : Table("tool_image") {
    val runId = text("run_id")
    val toolUseId = text("tool_use_id")
    val mimeType = text("mime_type")
    val bytes = binary("bytes")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(runId, toolUseId)
}

/** Pure: whether a picture saved at [createdAt] is still kept at [now] under [ttl]. */
fun pictureKept(createdAt: Instant, now: Instant, ttl: Duration): Boolean = now - createdAt < ttl

/**
 * The pictures tools showed, in Postgres (`tool_image`), for the cache's time to live (a day by default) and
 * dropped by the attachment sweep. The journal and transcripts never hold the bytes.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgToolImageStore(private val database: Database, private val clock: Clock, private val config: AppConfig) : ToolImageStore {
    private val ttl get() = config.attachments.ttl

    override suspend fun save(runId: String, toolUseId: String, image: ToolImage) {
        val bytes = Base64.getDecoder().decode(image.base64)
        val now = clock.now()
        database.tx {
            ToolImages.upsert {
                it[ToolImages.runId] = runId
                it[ToolImages.toolUseId] = toolUseId
                it[mimeType] = image.mimeType
                it[ToolImages.bytes] = bytes
                it[createdAt] = now
            }
        }
    }

    override suspend fun load(runId: String, toolUseId: String): ToolImage? = database.tx {
        ToolImages.selectAll().where { (ToolImages.runId eq runId) and (ToolImages.toolUseId eq toolUseId) }.singleOrNull()
    }?.takeIf { pictureKept(it[ToolImages.createdAt], clock.now(), ttl) }
        ?.let { ToolImage(it[ToolImages.mimeType], Base64.getEncoder().encodeToString(it[ToolImages.bytes])) }

    /** Drops pictures older than [ttl]; returns how many. */
    suspend fun sweep(now: Instant, ttl: Duration): Int = database.tx { ToolImages.deleteWhere { createdAt less (now - ttl) } }
}
