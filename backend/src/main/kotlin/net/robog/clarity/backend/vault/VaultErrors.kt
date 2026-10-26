package net.robog.clarity.backend.vault

import io.ktor.http.HttpStatusCode
import net.robog.clarity.backend.http.HttpProblem

/** Failures of the vault API. Details never contain secret values. */
sealed interface VaultError : HttpProblem {
    data class Invalid(override val detail: String) : VaultError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid vault request"
    }

    data class NoDeviceKey(val device: String) : VaultError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "No vault key"
        override val detail = "device '$device' has not registered a vault key"
    }

    data class RequestNotFound(val id: String) : VaultError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown credential request"
        override val detail = "no credential request '$id'"
    }

    data class RequestNotOpen(val id: String) : VaultError {
        override val status = HttpStatusCode.Conflict
        override val title = "Request already answered"
        override val detail = "credential request '$id' is no longer open"
    }

    data class NameTaken(val name: String) : VaultError {
        override val status = HttpStatusCode.Conflict
        override val title = "Credential name taken"
        override val detail = "there is already a credential called '$name' (pass replace=true to have the person enter it again)"
    }

    data class CredentialNotFound(val id: String) : VaultError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown credential"
        override val detail = "no credential '$id'"
    }

    data class EnvelopeNotFound(val id: String) : VaultError {
        override val status = HttpStatusCode.NotFound
        override val title = "No envelope for this device"
        override val detail = "credential '$id' has no sealed copy for the calling device"
    }

    data class HeldNotFound(val id: String) : VaultError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown held action"
        override val detail = "no held action '$id'"
    }

    /** The held action is not a vault action of the expected kind. */
    data class WrongKind(val id: String, val expected: String) : VaultError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "Wrong kind of held action"
        override val detail = "held action '$id' is not a $expected request"
    }

    data class NotPending(val id: String, val current: String) : VaultError {
        override val status = HttpStatusCode.Conflict
        override val title = "Already decided"
        override val detail = "held action '$id' is already $current"
    }

    data class ApprovalRequired(override val detail: String) : VaultError {
        override val status = HttpStatusCode.Forbidden
        override val title = "Approval not verified"
    }
}
