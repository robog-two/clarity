package net.robog.clarity.backend.http

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.Problem

/**
 * A typed domain error that knows how it appears on the wire (RFC 9457). Domain error
 * hierarchies implement this so routes can turn any `Either<E, A>` into a response without
 * a central `when` that every new error would have to touch.
 */
interface HttpProblem {
    val status: HttpStatusCode
    val title: String
    val detail: String?
}

/** Generic problems for the edge itself (malformed input, missing credentials, bugs). */
sealed interface EdgeProblem : HttpProblem {
    data class BadRequest(override val detail: String?) : EdgeProblem {
        override val status = HttpStatusCode.BadRequest
        override val title = "Bad request"
    }

    data class Unauthorized(override val detail: String? = null) : EdgeProblem {
        override val status = HttpStatusCode.Unauthorized
        override val title = "Authentication required"
    }

    data class NotFound(override val detail: String? = null) : EdgeProblem {
        override val status = HttpStatusCode.NotFound
        override val title = "Not found"
    }

    data class MethodNotAllowed(override val detail: String? = null) : EdgeProblem {
        override val status = HttpStatusCode.MethodNotAllowed
        override val title = "Method not allowed"
    }

    data class UnsupportedMediaType(override val detail: String? = null) : EdgeProblem {
        override val status = HttpStatusCode.UnsupportedMediaType
        override val title = "Unsupported media type"
    }

    data class Unavailable(override val detail: String? = null) : EdgeProblem {
        override val status = HttpStatusCode.ServiceUnavailable
        override val title = "Service unavailable"
    }

    /** Deliberately detail-free: internal failures must not leak. The call id correlates with logs. */
    data object Internal : EdgeProblem {
        override val status = HttpStatusCode.InternalServerError
        override val title = "Internal server error"
        override val detail: String? = null
    }
}

/** Throw to abort a request with a specific problem from code that cannot use `Raise`. */
class ProblemException(val problem: HttpProblem) : RuntimeException(problem.title)

val ContentTypeProblemJson: ContentType = ContentType.Application.ProblemJson

fun HttpProblem.toProblem(): Problem = Problem(title = title, status = status.value, detail = detail)

suspend fun ApplicationCall.respondProblem(problem: HttpProblem) {
    respondText(
        text = ClarityJson.encodeToString(Problem.serializer(), problem.toProblem()),
        contentType = ContentTypeProblemJson,
        status = problem.status,
    )
}

/** Responds with [ok] on the right, or the left problem. */
suspend inline fun <reified A : Any> ApplicationCall.respondEither(
    result: Either<HttpProblem, A>,
    ok: HttpStatusCode = HttpStatusCode.OK,
) = result.fold({ respondProblem(it) }, { respond(ok, it) })
