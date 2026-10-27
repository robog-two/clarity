package net.robog.clarity.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.robog.clarity.app.push.LocalFrontScreen
import net.robog.clarity.app.ui.held.LocalHeldExtras
import net.robog.clarity.app.vault.LocalVaultRepository
import net.robog.clarity.app.vault.vaultExtras
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import net.robog.clarity.app.call.CallActivity
import net.robog.clarity.app.call.LaunchKind
import net.robog.clarity.app.call.launchKind
import net.robog.clarity.app.call.resumeOnLaunch
import net.robog.clarity.app.nav.toUri
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import net.robog.clarity.app.nav.ClarityNavHost
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.parseDeepLink
import net.robog.clarity.app.ui.theme.ClarityTheme

class MainActivity : FragmentActivity() {
    /** The link that opened (or re-opened) the app, until the navigation has followed it. */
    private val pendingLink = mutableStateOf<DeepLink?>(null)

    /** How the app was just opened, until [onResume] has decided whether to go back into the call. */
    private var pendingLaunch: LaunchKind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ClarityApp).graph
        if (savedInstanceState == null) {
            pendingLink.value = linkOf(intent)
            pendingLaunch = launchKindOf(intent)
        } else if (!createdInProcess) {
            // Recreated after the process was gone (recents): coming back, not a rotation.
            pendingLaunch = LaunchKind.Returned
        }
        createdInProcess = true
        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                LocalVaultRepository provides graph.vaultRepository,
                LocalHeldExtras provides vaultExtras,
                LocalFrontScreen provides graph.frontScreen,
            ) {
                ClarityTheme {
                    ClarityNavHost(deepLink = pendingLink.value, onDeepLinkHandled = { pendingLink.value = null })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val graph = (application as ClarityApp).graph
        // The biometric prompt needs the activity in front.
        graph.foregroundActivity.set(this)
        graph.frontScreen.appVisible(true)
        graph.pushNotifier.clearConversation()
        // Make sure the server knows this phone's vault key and approval key (a no-op when nothing is set up yet).
        lifecycleScope.launch(Dispatchers.Default) {
            if (graph.sessionStore.session.first() != null) graph.vaultRepository.register()
        }
        // A call still going on (its screen swiped away, or the process restarted) shows as a call again.
        lifecycleScope.launch(Dispatchers.Default) {
            if (graph.sessionStore.session.first() != null) graph.ongoingCallStarter.ensure()
        }
    }

    override fun onRestart() {
        super.onRestart()
        pendingLaunch = LaunchKind.Returned
    }

    override fun onResume() {
        super.onResume()
        val kind = pendingLaunch ?: return
        pendingLaunch = null
        val tracker = (application as ClarityApp).graph.callTracker
        val sessions = (application as ClarityApp).graph.sessionStore
        lifecycleScope.launch {
            val resume = withContext(Dispatchers.Default) {
                if (sessions.session.first() == null) return@withContext false
                if (kind == LaunchKind.Link) {
                    // Asked for another screen: that is choosing the app over the call for now.
                    tracker.live()?.let { tracker.choseApp(it.id) }
                    return@withContext false
                }
                if (kind == LaunchKind.Other) return@withContext false
                // Cached when fresh, else a quick refresh; a failure just leaves Today showing.
                resumeOnLaunch(kind, tracker.liveNow(), tracker.choseAppOver)
            }
            if (resume) {
                tracker.ring(false)
                startActivity(
                    Intent(Intent.ACTION_VIEW, DeepLink.Call().toUri().toUri(), this@MainActivity, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
    }

    override fun onStop() {
        (application as ClarityApp).graph.foregroundActivity.set(null)
        (application as ClarityApp).graph.frontScreen.appVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink.value = linkOf(intent)
        pendingLaunch = launchKindOf(intent)
    }

    private fun launchKindOf(intent: Intent): LaunchKind =
        launchKind(intent.action, hasLink = parseDeepLink(intent.dataString) != null)

    /** The call has its own activity: a call link that lands here is handed over. */
    private fun linkOf(intent: Intent): DeepLink? {
        val link = parseDeepLink(intent.dataString)
        if (link is DeepLink.Call) {
            startActivity(Intent(intent).setClass(this, CallActivity::class.java))
            return null
        }
        return link
    }

    private companion object {
        /** Whether this process already created the activity once (so a saved state means a rotation). */
        @Volatile var createdInProcess = false
    }
}
