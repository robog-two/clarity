package net.robog.clarity.backend.connectors

import io.ktor.http.HttpStatusCode
import net.robog.clarity.backend.http.HttpProblem

/** Failures of the connector management API. */
sealed interface ConnectorError : HttpProblem {
    data class NotFound(val id: String) : ConnectorError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown connector"
        override val detail = "no connector with id '$id'"
    }

    data class InvalidId(val raw: String) : ConnectorError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid connector id"
        override val detail = "'$raw' does not match ${net.robog.clarity.shared.ConnectorId.PATTERN.pattern}"
    }
}
