package net.robog.clarity.backend.auth

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.Raise
import arrow.core.raise.context.either
import arrow.core.raise.context.bind
import arrow.core.raise.context.ensure
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.EnrollmentMode
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.api.InstanceInfo

private val log = KotlinLogging.logger {}

sealed interface EnrollmentError : HttpProblem {
    data class InvalidKey(val why: String) : EnrollmentError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid public key"
        override val detail: String = why
    }

    data object Forbidden : EnrollmentError {
        override val status = HttpStatusCode.Forbidden
        override val title = "Enrollment not permitted"
        override val detail: String = "a valid enrollment token is required"
    }
}

/**
 * Pure enrollment policy: a valid token always enrolls; otherwise only trust-on-first-use on an
 * instance that has no devices yet.
 */
context(_: Raise<EnrollmentError>)
fun authorizeEnrollment(mode: EnrollmentMode, existingDevices: Long, tokenValid: Boolean) {
    val tofu = mode == EnrollmentMode.TOFU && existingDevices == 0L
    ensure(tokenValid || tofu) { EnrollmentError.Forbidden }
}

@Inject
@SingleIn(AppScope::class)
class EnrollmentService(
    private val config: AppConfig,
    private val devices: DeviceRepository,
    private val tokens: EnrollmentTokenStore,
    private val instance: InstanceInfo,
) {
    // Serializes the "zero devices?" check with the insert so two TOFU races cannot both win.
    private val lock = Mutex()

    suspend fun enroll(request: EnrollmentRequest): Either<EnrollmentError, EnrollmentResponse> = lock.withLock {
        val key = decodeBase64Url(request.publicKey).flatMap { parseP256PublicKey(it) }
            .mapLeft { EnrollmentError.InvalidKey(it.reason) }
        val tokenValid = request.enrollmentToken?.let { tokens.matches(it) } ?: false
        val existing = devices.count()
        either {
            val publicKey = key.bind()
            authorizeEnrollment(config.enrollment.mode, existing, tokenValid)
            val id = devices.register(request.deviceName.trim().ifEmpty { "device" }, publicKey.encoded)
            log.info { "enrolled device $id (${request.deviceName})" }
            EnrollmentResponse(id, instance)
        }
    }
}
