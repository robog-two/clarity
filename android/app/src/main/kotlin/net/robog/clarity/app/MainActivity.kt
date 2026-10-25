package net.robog.clarity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import net.robog.clarity.app.nav.ClarityNavHost
import net.robog.clarity.app.ui.theme.ClarityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ClarityApp).graph
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
                ClarityTheme { ClarityNavHost() }
            }
        }
    }
}
