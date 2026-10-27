package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.browser.BrowserStepView
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.jsonb

private val log = KotlinLogging.logger {}

/** `browser_step` (V20): the browser pilot's audit trail. */
object BrowserSteps : Table("browser_step") {
    val id = long("id").autoIncrement()
    val runId = text("run_id")
    val seq = integer("seq")
    val at = timestamp("at")
    val tool = text("tool")
    val tabUrl = text("tab_url").nullable()
    val tabTitle = text("tab_title").nullable()
    val intent = text("intent").nullable()
    val expect = text("expect").nullable()
    val beforePng = binary("before_png").nullable()
    val beforeWithheld = bool("before_withheld")
    val box = jsonb<JsonObject>("box", ClarityJson).nullable()
    val hitRole = text("hit_role").nullable()
    val hitName = text("hit_name").nullable()
    val jev = jsonb<JsonObject>("jev", ClarityJson).nullable()
    val gate = text("gate")
    val action = text("action").nullable()
    val afterPng = binary("after_png").nullable()
    val afterWithheld = bool("after_withheld")
    val changeReport = text("change_report").nullable()
    val costMicros = long("cost_micros").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * One audit row before it is written. A frame is either bytes or withheld: [beforeWithheld] /
 * [afterWithheld] mark a screenshot not kept because a secret may have been on screen.
 */
data class BrowserStepRecord(
    val runId: String,
    val seq: Int,
    val at: Instant,
    val tool: String,
    val tabUrl: String?,
    val tabTitle: String?,
    val intent: String?,
    val expect: String?,
    val beforePng: ByteArray?,
    val beforeWithheld: Boolean,
    val box: JsonObject?,
    val hitRole: String?,
    val hitName: String?,
    val jev: JsonObject?,
    val gate: String,
    val action: String?,
    val afterPng: ByteArray?,
    val afterWithheld: Boolean,
    val changeReport: String?,
    val costMicros: Long?,
) {
    init {
        require(!(beforeWithheld && beforePng != null) && !(afterWithheld && afterPng != null)) { "a withheld frame is never stored" }
    }
}

/** Where the pilot's audit rows go. A seam: tests keep them in memory. */
interface BrowserAudit {
    suspend fun record(step: BrowserStepRecord)
    suspend fun steps(runId: String): List<BrowserStepView>

    /** A stored screenshot of step [id]: [after] false for the one before the step. */
    suspend fun screenshot(id: Long, after: Boolean): ByteArray?
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgBrowserAudit(private val database: Database) : BrowserAudit {
    override suspend fun record(step: BrowserStepRecord) {
        try {
            database.tx {
                BrowserSteps.insert {
                    it[runId] = step.runId
                    it[seq] = step.seq
                    it[at] = step.at
                    it[tool] = step.tool
                    it[tabUrl] = step.tabUrl
                    it[tabTitle] = step.tabTitle
                    it[intent] = step.intent
                    it[expect] = step.expect
                    it[beforePng] = step.beforePng
                    it[beforeWithheld] = step.beforeWithheld
                    it[box] = step.box
                    it[hitRole] = step.hitRole
                    it[hitName] = step.hitName
                    it[jev] = step.jev
                    it[gate] = step.gate
                    it[action] = step.action
                    it[afterPng] = step.afterPng
                    it[afterWithheld] = step.afterWithheld
                    it[changeReport] = step.changeReport
                    it[costMicros] = step.costMicros
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The audit trail must never break a step the person may be waiting on.
            log.warn { "browser step ${step.runId}#${step.seq} not recorded: ${e::class.simpleName}" }
        }
    }

    override suspend fun steps(runId: String): List<BrowserStepView> = database.tx {
        val cols = BrowserSteps.columns - BrowserSteps.beforePng - BrowserSteps.afterPng
        val hasBefore = BrowserSteps.beforePng.isNotNull()
        val hasAfter = BrowserSteps.afterPng.isNotNull()
        BrowserSteps.select(cols + listOf(hasBefore, hasAfter))
            .where { BrowserSteps.runId eq runId }
            .orderBy(BrowserSteps.seq to SortOrder.ASC)
            .map { r ->
                BrowserStepView(
                    id = r[BrowserSteps.id], runId = r[BrowserSteps.runId], seq = r[BrowserSteps.seq], at = r[BrowserSteps.at], tool = r[BrowserSteps.tool],
                    tabUrl = r[BrowserSteps.tabUrl], tabTitle = r[BrowserSteps.tabTitle], intent = r[BrowserSteps.intent], expect = r[BrowserSteps.expect],
                    beforeShot = r[hasBefore], beforeWithheld = r[BrowserSteps.beforeWithheld], box = r[BrowserSteps.box],
                    hitRole = r[BrowserSteps.hitRole], hitName = r[BrowserSteps.hitName], jev = r[BrowserSteps.jev], gate = r[BrowserSteps.gate],
                    action = r[BrowserSteps.action], afterShot = r[hasAfter], afterWithheld = r[BrowserSteps.afterWithheld],
                    changeReport = r[BrowserSteps.changeReport], costMicros = r[BrowserSteps.costMicros],
                )
            }
    }

    override suspend fun screenshot(id: Long, after: Boolean): ByteArray? = database.tx {
        val col = if (after) BrowserSteps.afterPng else BrowserSteps.beforePng
        BrowserSteps.select(col).where { BrowserSteps.id eq id }.singleOrNull()?.get(col)
    }
}

/** In memory, for tests. */
class MemoryBrowserAudit : BrowserAudit {
    val rows = CopyOnWriteArrayList<BrowserStepRecord>()

    override suspend fun record(step: BrowserStepRecord) { rows += step }

    override suspend fun steps(runId: String): List<BrowserStepView> = rows.withIndex().filter { it.value.runId == runId }.map { (i, s) ->
        BrowserStepView(
            i.toLong() + 1, s.runId, s.seq, s.at, s.tool, s.tabUrl, s.tabTitle, s.intent, s.expect, s.beforePng != null, s.beforeWithheld, s.box,
            s.hitRole, s.hitName, s.jev, s.gate, s.action, s.afterPng != null, s.afterWithheld, s.changeReport, s.costMicros,
        )
    }

    override suspend fun screenshot(id: Long, after: Boolean): ByteArray? = rows.getOrNull(id.toInt() - 1)?.let { if (after) it.afterPng else it.beforePng }
}

/** The audit trail for the app, read-only and device-authenticated (no app UI yet). */
@Inject
@ContributesIntoSet(AppScope::class)
class BrowserAuditRoutes(private val audit: BrowserAudit) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route(ApiPaths.BROWSER_STEPS) {
                    get {
                        val run = call.request.queryParameters["run"]
                        if (run.isNullOrBlank()) call.respondProblem(EdgeProblem.BadRequest("run is required"))
                        else call.respond(audit.steps(run))
                    }
                    get("{id}/{shot}") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        val after = when (call.parameters["shot"]) {
                            "before.png" -> false
                            "after.png" -> true
                            else -> null
                        }
                        val png = if (id == null || after == null) null else audit.screenshot(id, after)
                        if (png == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(png, ContentType.Image.PNG)
                    }
                }
            }
        }
    }
}
