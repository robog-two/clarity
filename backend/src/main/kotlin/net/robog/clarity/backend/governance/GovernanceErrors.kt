package net.robog.clarity.backend.governance

import io.ktor.http.HttpStatusCode
import net.robog.clarity.backend.http.HttpProblem

/** Failures of the governance API and services. */
sealed interface GovernanceError : HttpProblem {
    data class NoConstitution(val version: Int? = null) : GovernanceError {
        override val status = HttpStatusCode.NotFound
        override val title = "No constitution"
        override val detail = version?.let { "there is no constitution version $it" }
            ?: "the values interview has not produced a constitution yet"
    }

    data object AlreadyInitialized : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Constitution already exists"
        override val detail = "change it with an amendment"
    }

    data class InvalidConstitution(override val detail: String) : GovernanceError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid constitution"
    }

    data class LineNotFound(val id: String) : GovernanceError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "Unknown line"
        override val detail = "the constitution has no line '$id'"
    }

    data class AmendmentNotFound(val id: String) : GovernanceError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown amendment"
        override val detail = "no amendment '$id'"
    }

    data class AmendmentNotProposed(val id: String) : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Amendment already decided"
        override val detail = "amendment '$id' is no longer proposed"
    }

    data class HeldNotFound(val id: String) : GovernanceError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown held action"
        override val detail = "no held action '$id'"
    }

    data class HeldNotPending(val id: String, val current: String) : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Already decided"
        override val detail = "held action '$id' is already $current"
    }

    data class HardLine(override val detail: String) : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Hard line"
    }

    data class InvalidEdit(override val detail: String) : GovernanceError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid edit"
    }

    data class ApproveElsewhere(override val detail: String) : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Cannot be approved here"
    }

    data class BadSignature(override val detail: String) : GovernanceError {
        override val status = HttpStatusCode.Forbidden
        override val title = "Approval not verified"
    }

    data class SignatureReplayed(val id: String) : GovernanceError {
        override val status = HttpStatusCode.Conflict
        override val title = "Signature already used"
        override val detail = "that signature was already used for '$id'"
    }

    data class NoHandler(val kind: String) : GovernanceError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "No connector can do this"
        override val detail = "no connector handles '$kind' actions"
    }

    data class ActionNotFound(val id: String) : GovernanceError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown action"
        override val detail = "no action '$id' for this connector"
    }
}
