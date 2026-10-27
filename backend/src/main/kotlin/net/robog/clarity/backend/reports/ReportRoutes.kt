package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportList

/** A render that failed on this report's content (422) or took too long (504). */
private data class RenderProblem(override val status: HttpStatusCode, override val title: String, override val detail: String?) : HttpProblem

/** Pure: how a download error appears on the wire. */
fun DownloadError.toProblem(): HttpProblem = when (this) {
    is DownloadError.NotFound -> EdgeProblem.NotFound(message)
    is DownloadError.Unsupported -> EdgeProblem.BadRequest(message)
    is DownloadError.Render -> when (error) {
        is RenderError.Failed -> RenderProblem(HttpStatusCode.UnprocessableEntity, "Could not render this report", message)
        is RenderError.TimedOut -> RenderProblem(HttpStatusCode.GatewayTimeout, "Rendering took too long", message)
        is RenderError.Unavailable -> EdgeProblem.Unavailable(message)
    }
}

/**
 * Reports for the app, device-authenticated like the rest of `/api/v1` (there is no public route):
 *  - `GET /api/v1/reports`: the list, newest first;
 *  - `GET /api/v1/reports/{id}`: metadata, revisions, sources and the formats (labels included);
 *  - `GET /api/v1/reports/{id}/source?rev=`: a revision's Markdown (the latest without `rev`);
 *  - `GET /api/v1/reports/{id}/download/{format}?rev=`: the rendered file, as an attachment named
 *    after the title. Rendered on demand and cached.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class ReportRoutes(private val reports: ReportService, private val store: ReportStore) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(ApiPaths.REPORTS) {
                    call.respond(ReportList(store.list()))
                }
                get("${ApiPaths.REPORTS}/{id}") {
                    val detail = reports.detail(call.parameters["id"].orEmpty())
                    if (detail == null) call.respondProblem(EdgeProblem.NotFound("No such report.")) else call.respond(detail)
                }
                get("${ApiPaths.REPORTS}/{id}/source") {
                    val rev = call.revParameter() ?: return@get
                    val revision = reports.source(call.parameters["id"].orEmpty(), rev.value)
                    if (revision == null) {
                        call.respondProblem(EdgeProblem.NotFound("No such report or revision."))
                    } else {
                        call.response.header("X-Report-Revision", revision.n.toString())
                        call.respondText(revision.source, ContentType.parse(ReportFormat.MD.mimeType))
                    }
                }
                get("${ApiPaths.REPORTS}/{id}/download/{format}") {
                    val format = ReportFormat.fromWire(call.parameters["format"].orEmpty())
                    if (format == null) {
                        call.respondProblem(EdgeProblem.BadRequest("format must be one of ${ReportFormat.entries.joinToString { it.wireName }}"))
                        return@get
                    }
                    val rev = call.revParameter() ?: return@get
                    reports.download(call.parameters["id"].orEmpty(), rev.value, format).fold(
                        { call.respondProblem(it.toProblem()) },
                        { file ->
                            call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file.filename))
                            call.response.header("X-Content-Type-Options", "nosniff")
                            call.respondBytes(file.bytes, ContentType.parse(file.format.mimeType))
                        },
                    )
                }
            }
        }
    }

    /** `rev` as a holder (its value null when absent), or null after answering 400 for a bad one. */
    private suspend fun ApplicationCall.revParameter(): Rev? {
        val raw = request.queryParameters["rev"] ?: return Rev(null)
        val n = raw.toIntOrNull()?.takeIf { it >= 1 }
        if (n == null) {
            respondProblem(EdgeProblem.BadRequest("rev must be a revision number (1, 2, ...)"))
            return null
        }
        return Rev(n)
    }

    private class Rev(val value: Int?)
}
