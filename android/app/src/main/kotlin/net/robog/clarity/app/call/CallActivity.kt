package net.robog.clarity.app.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ClarityApp
import net.robog.clarity.app.MainActivity
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.parseDeepLink
import net.robog.clarity.app.nav.toUri
import net.robog.clarity.app.ui.call.CallContent
import net.robog.clarity.app.ui.call.CallViewModel
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.voice.rememberMicAccess

/**
 * The morning call on its own task: it shows over the lock screen and turns the screen on (the
 * full-screen intent of the incoming-call notification), and nothing else of the app is reachable
 * from it until the person taps "open the app".
 */
class CallActivity : ComponentActivity() {
    private var answerRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        answerRequested = (parseDeepLink(intent?.dataString) as? DeepLink.Call)?.answer == true
        val graph = (application as ClarityApp).graph
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
                ClarityTheme {
                    val viewModel: CallViewModel = metroViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    val mic = rememberMicAccess(viewModel::onMicPermission)
                    LaunchedEffect(lifecycle) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepInSync() }
                    }
                    LaunchedEffect(lifecycle) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.setForeground(true)
                            try { awaitCancellation() } finally { viewModel.setForeground(false) }
                        }
                    }
                    // Answer from the notification once the screen knows where the call stands.
                    LaunchedEffect(state.screen, answerRequested) {
                        if (answerRequested && state.screen == net.robog.clarity.app.ui.call.CallScreen.Incoming) {
                            answerRequested = false
                            viewModel.answer()
                        }
                    }
                    LaunchedEffect(state.screen) {
                        if (state.screen == net.robog.clarity.app.ui.call.CallScreen.InCall && !mic.granted) mic.request()
                    }
                    CallContent(
                        state = state,
                        mic = mic,
                        onAnswer = viewModel::answer,
                        onDecline = viewModel::decline,
                        onAdvance = viewModel::advance,
                        onToggleMute = viewModel::toggleMute,
                        onReplay = viewModel::replay,
                        onCloseReplay = viewModel::closeReplay,
                        onOpenApp = ::openApp,
                        onDraft = viewModel::onDraft,
                        onSend = viewModel::sendTyped,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerRequested = (parseDeepLink(intent.dataString) as? DeepLink.Call)?.answer == true
    }

    private fun openApp() {
        startActivity(
            Intent(Intent.ACTION_VIEW, DeepLink.Today.toUri().toUri(), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
