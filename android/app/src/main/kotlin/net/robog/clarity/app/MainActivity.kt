package net.robog.clarity.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import net.robog.clarity.app.call.CallActivity
import net.robog.clarity.app.nav.ClarityNavHost
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.parseDeepLink
import net.robog.clarity.app.ui.theme.ClarityTheme

class MainActivity : ComponentActivity() {
    /** The link that opened (or re-opened) the app, until the navigation has followed it. */
    private val pendingLink = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ClarityApp).graph
        if (savedInstanceState == null) pendingLink.value = linkOf(intent)
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
                ClarityTheme {
                    ClarityNavHost(deepLink = pendingLink.value, onDeepLinkHandled = { pendingLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink.value = linkOf(intent)
    }

    /** The call has its own activity: a call link that lands here is handed over. */
    private fun linkOf(intent: Intent): DeepLink? {
        val link = parseDeepLink(intent.dataString)
        if (link is DeepLink.Call) {
            startActivity(Intent(intent).setClass(this, CallActivity::class.java))
            return null
        }
        return link
    }
}
