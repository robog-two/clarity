package net.robog.clarity.app.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import java.io.File
import kotlinx.serialization.Serializable
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.ask.AskContent
import net.robog.clarity.app.ui.ask.AskUiState
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldAction

/**
 * Renders your own thread, exported from the server into `app/screenshot-local/thread.json`
 * (gitignored, as are the images these produce). Without the file each preview says so.
 */
@Serializable
private data class LocalThread(val items: List<FeedItem> = emptyList(), val held: List<HeldAction>? = null)

private val thread: LocalThread by lazy {
    val file = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "screenshot-local/thread.json"), File(it, "app/screenshot-local/thread.json")) }
        .firstOrNull { it.isFile }
    file?.let { ClarityJson.decodeFromString(LocalThread.serializer(), it.readText()) } ?: LocalThread()
}

private const val PAGE = 10
private const val PAGES = 20

class ThreadPages : PreviewParameterProvider<Int> {
    override val values = (0 until PAGES).asSequence()
}

/** Indexes of items with anything besides plain text and said bubbles (up to 48). */
class RichItems : PreviewParameterProvider<Int> {
    override val values = (0 until 48).asSequence()
}

private val richIndexes: List<Int> by lazy {
    thread.items.indices.filter { i -> thread.items[i].cards.any { it !is Card.Text && it !is Card.Said } }
}

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun LocalRichItem(@PreviewParameter(RichItems::class) n: Int) {
    val item = richIndexes.getOrNull(n)?.let { thread.items[it] }
    val env = CardEnv(held = thread.held.orEmpty().associateBy { it.id })
    ClarityTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item == null) Text("(no item)") else {
                    Kicker("#${item.seq}")
                    FeedItemView(item, env = env)
                }
            }
        }
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 2600)
@Composable
fun LocalThreadPage(@PreviewParameter(ThreadPages::class) page: Int) {
    val items = thread.items.drop(page * PAGE).take(PAGE)
    val env = CardEnv(held = thread.held.orEmpty().associateBy { it.id })
    ClarityTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) Text("no items on this page (export app/screenshot-local/thread.json)")
            items.forEach {
                Kicker("#${it.seq}", Modifier.fillMaxWidth())
                FeedItemView(it, env = env)
            }
        } }
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun LocalAskScreenLight() = LocalAsk(dark = false)

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun LocalAskScreenDark() = LocalAsk(dark = true)

@Composable
private fun LocalAsk(dark: Boolean) {
    val state = AskUiState(
        items = thread.items.takeLast(6),
        held = thread.held.orEmpty().associateBy { it.id },
        sync = SyncState.Live,
    )
    ClarityTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            AskContent(state, MicAccess(true, false, {}, {}), {}, {}, {}, {}, { _, _ -> })
        }
    }
}
