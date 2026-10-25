package net.robog.clarity.app.data

import android.os.Build
import kotlinx.coroutines.flow.Flow
import net.robog.clarity.app.identity.DeviceKey
import net.robog.clarity.app.net.ClarityApi
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.InstanceInfo

/** Enrolls this device with an instance and persists the resulting [Session]. */
interface EnrollmentRepository {
    suspend fun enroll(instanceUrl: String, enrollmentToken: String?): ApiResult<InstanceInfo>
}

/** The current enrollment: observe it, or forget it. */
interface SessionRepository {
    val session: Flow<Session?>
    suspend fun forget()
}

interface InstanceRepository {
    suspend fun info(): ApiResult<InstanceInfo>
}

class DefaultEnrollmentRepository(
    private val api: ClarityApi,
    private val key: DeviceKey,
    private val sessions: SessionStore,
) : EnrollmentRepository {
    override suspend fun enroll(instanceUrl: String, enrollmentToken: String?): ApiResult<InstanceInfo> {
        val request = EnrollmentRequest(
            publicKey = key.publicKeySpki(), // reuses the existing key if present
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            enrollmentToken = enrollmentToken?.trim()?.takeIf { it.isNotEmpty() },
        )
        return when (val result = api.enroll(instanceUrl, request)) {
            is ApiResult.Success -> {
                sessions.save(Session(instanceUrl, result.value.deviceId))
                ApiResult.Success(result.value.instance)
            }
            is ApiResult.Failure -> result
        }
    }
}

class DefaultSessionRepository(
    private val sessions: SessionStore,
    private val key: DeviceKey,
) : SessionRepository {
    override val session: Flow<Session?> = sessions.session

    override suspend fun forget() {
        sessions.clear()
        key.delete()
    }
}

class DefaultInstanceRepository(private val api: ClarityApi) : InstanceRepository {
    override suspend fun info(): ApiResult<InstanceInfo> = api.instance()
}
