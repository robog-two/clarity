package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.robog.clarity.app.AppConfig
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.EnrollmentRepository
import net.robog.clarity.app.data.UrlError
import net.robog.clarity.app.ui.onboarding.OnboardingAction
import net.robog.clarity.app.ui.onboarding.OnboardingError
import net.robog.clarity.app.ui.onboarding.OnboardingState
import net.robog.clarity.app.ui.onboarding.OnboardingViewModel
import net.robog.clarity.app.ui.onboarding.reduce
import net.robog.clarity.shared.api.InstanceInfo

private val info = InstanceInfo("Home", "1.0")

class OnboardingReducerTest {
    @Test fun typingClearsErrorAndEnablesSubmit() {
        val s = reduce(OnboardingState(error = OnboardingError.Unreachable), OnboardingAction.UrlChanged("https://x"))
        assertNull(s.error)
        assertTrue(s.canSubmit)
    }

    @Test fun connectLifecycle() {
        val started = reduce(OnboardingState(url = "https://x"), OnboardingAction.ConnectStarted)
        assertTrue(started.connecting)
        assertTrue(!started.canSubmit)
        val ok = reduce(started, OnboardingAction.ConnectFinished(ApiResult.Success(info)))
        assertTrue(!ok.connecting && ok.error == null)
    }

    @Test fun failuresMapToErrors() {
        fun err(e: ApiError) =
            reduce(OnboardingState(connecting = true), OnboardingAction.ConnectFinished(ApiResult.Failure(e))).error
        assertEquals(OnboardingError.Unreachable, err(ApiError.Unreachable("x")))
        assertEquals(OnboardingError.TokenRequired, err(ApiError.Unauthorized(null)))
        assertEquals(OnboardingError.TokenRequired, err(ApiError.Forbidden(null)))
        assertEquals(OnboardingError.Server(500), err(ApiError.Http(500, null)))
        assertEquals(OnboardingError.Unexpected, err(ApiError.Malformed(null)))
    }

    @Test fun urlRejection() {
        val s = reduce(OnboardingState(), OnboardingAction.UrlRejected(UrlError.InsecureScheme))
        assertEquals(OnboardingError.InvalidUrl(UrlError.InsecureScheme), s.error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class FakeEnrollment(var result: ApiResult<InstanceInfo>) : EnrollmentRepository {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun enroll(instanceUrl: String, enrollmentToken: String?): ApiResult<InstanceInfo> {
            calls += instanceUrl to enrollmentToken
            return result
        }
    }

    private inline fun withMain(block: () -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun invalidUrlNeverCallsRepository() = withMain {
        val repo = FakeEnrollment(ApiResult.Success(info))
        val vm = OnboardingViewModel(repo, AppConfig(allowCleartext = false))
        vm.onUrlChanged("http://insecure.example")
        vm.connect()
        assertEquals(OnboardingError.InvalidUrl(UrlError.InsecureScheme), vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun successSendsNormalizedUrlAndToken() = withMain {
        val repo = FakeEnrollment(ApiResult.Success(info))
        val vm = OnboardingViewModel(repo, AppConfig(allowCleartext = false))
        vm.onUrlChanged("https://Clarity.example.com/")
        vm.onTokenChanged("tok")
        vm.connect()
        assertEquals(listOf<Pair<String, String?>>("https://clarity.example.com" to "tok"), repo.calls)
        assertTrue(!vm.state.value.connecting)
        assertNull(vm.state.value.error)
    }

    @Test fun unauthorizedShowsTokenRequired() = withMain {
        val repo = FakeEnrollment(ApiResult.Failure(ApiError.Unauthorized(null)))
        val vm = OnboardingViewModel(repo, AppConfig(allowCleartext = true))
        vm.onUrlChanged("http://10.0.2.2:8080")
        vm.connect()
        assertEquals(OnboardingError.TokenRequired, vm.state.value.error)
    }
}
