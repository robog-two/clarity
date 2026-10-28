package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.cards.AgentBubble
import net.robog.clarity.app.ui.cards.SaidBubble
import net.robog.clarity.app.ui.goods.DownloadState
import net.robog.clarity.app.ui.goods.GoodsContent
import net.robog.clarity.app.ui.goods.GoodsFormat
import net.robog.clarity.app.ui.goods.GoodsUiState
import net.robog.clarity.app.ui.goods.ReportCardView
import net.robog.clarity.app.ui.goods.ReportContent
import net.robog.clarity.app.ui.goods.ReportUiState
import net.robog.clarity.app.ui.goods.goodsRows
import net.robog.clarity.app.ui.goods.parseMarkdown
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.report.ReportCitation
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportSummary

private val now = Instant.parse("2026-10-28T15:00:00Z")

@Composable
private fun Goods(dark: Boolean, content: @Composable () -> Unit) {
    ClarityTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.surface) { content() } }
}

// ---------- the card in the thread ----------

private val devilsCard = Card.Report("r1", "Possession and Power in The Devils", listOf("pdf", "docx", "odt", "txt", "md"))

@Composable
private fun ReportCardInThread(dark: Boolean, busy: String? = null) = Goods(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SaidBubble("Create a report about background for The Devils and put it in my goods tab")
        AgentBubble("Done. The background on The Devils is in your Goods, with the two sources it draws on.")
        ReportCardView(devilsCard, onOpen = {}, previewBusy = busy)
    }
}

@PreviewTest @Preview(widthDp = 412, heightDp = 420) @Composable fun ReportCardLight() = ReportCardInThread(false)
@PreviewTest @Preview(widthDp = 412, heightDp = 420) @Composable fun ReportCardDark() = ReportCardInThread(true)
@PreviewTest @Preview(widthDp = 412, heightDp = 420) @Composable fun ReportCardDownloading() = ReportCardInThread(false, busy = "pdf")

// ---------- the viewer ----------

private val essay = """
---
title: "Possession and Power in The Devils"
author: Sam Robinson
date: October 28, 2026
---

# Background

Ken Russell's *The Devils* (1971) adapts Aldous Huxley's **The Devils of Loudun** (1952) and John Whiting's play of 1960. It retells the 1634 trial of Urbain Grandier, a priest burned after the Ursuline nuns of Loudun claimed he had bewitched them.

## Where the story comes from

- Huxley's history of the Loudun possessions
- Whiting's stage adaptation for the Royal Shakespeare Company
- Contemporary accounts of the public exorcisms

> "The film is about the abuse of power by church and state." — Ken Russell

Warner Bros. cut the film heavily on release, and the full version is still [hard to see](https://www.bfi.org.uk/features/devils).

## Works Cited

1. Huxley, Aldous. *The Devils of Loudun*. Chatto & Windus, 1952.
2. Russell, Ken, director. *The Devils*. Warner Bros., 1971.
""".trimIndent()

private val formats = listOf(
    GoodsFormat("pdf", "PDF"), GoodsFormat("docx", "Word / Pages"), GoodsFormat("odt", "OpenDocument"),
    GoodsFormat("txt", "Text"), GoodsFormat("md", "Markdown"),
)

private val viewer = ReportUiState(
    loading = false,
    title = "Possession and Power in The Devils",
    subtitle = "Document · revision 2 · Today, 2:40 pm",
    document = parseMarkdown(essay),
    formats = formats,
    sources = listOf(
        ReportCitation("https://en.wikipedia.org/wiki/The_Devils_(film)", "The Devils (film)", "directed by Ken Russell", now),
        ReportCitation("https://www.bfi.org.uk/features/devils", "The Devils at 50: Ken Russell's most notorious film", null, now),
    ),
)

@Composable
private fun Viewer(dark: Boolean, state: ReportUiState) = Goods(dark) { ReportContent(state, {}, "Goods", { _, _ -> }, {}) }

@PreviewTest @Preview(widthDp = 412, heightDp = 1500) @Composable fun ReportViewerLight() = Viewer(false, viewer)
@PreviewTest @Preview(widthDp = 412, heightDp = 1500) @Composable fun ReportViewerDark() = Viewer(true, viewer)
@PreviewTest @Preview(widthDp = 412, heightDp = 700) @Composable fun ReportViewerDownloading() =
    Viewer(false, viewer.copy(downloads = mapOf("docx" to DownloadState.Busy, "pdf" to DownloadState.Failed("Can’t reach your server right now."))))
@PreviewTest @Preview(widthDp = 412, heightDp = 400) @Composable fun ReportViewerLoading() = Viewer(false, ReportUiState())
@PreviewTest @Preview(widthDp = 412, heightDp = 400) @Composable fun ReportViewerErrorLight() =
    Viewer(false, ReportUiState(loading = false, error = "Can’t reach your server right now."))
@PreviewTest @Preview(widthDp = 412, heightDp = 400) @Composable fun ReportViewerErrorDark() =
    Viewer(true, ReportUiState(loading = false, error = "Can’t reach your server right now."))

// ---------- the Goods tab ----------

private val goods = goodsRows(
    listOf(
        ReportSummary("r1", ReportKind.DOCUMENT, "Possession and Power in The Devils", now - 2.hours, 2, now - 20.minutes),
        ReportSummary("r2", ReportKind.DOCUMENT, "The causes of the 1918 flu pandemic", now - 1.days, 1, now - 1.days),
        ReportSummary("r3", ReportKind.DOCUMENT, "Cover letter for the Aldridge Lab internship", now - 9.days, 3, now - 6.days),
        ReportSummary("r4", ReportKind.DOCUMENT, "Notes from the October planning call", now - 30.days, 1, now - 30.days),
    ),
    now, TimeZone.UTC,
)

@Composable
private fun GoodsTab(dark: Boolean, state: GoodsUiState) = Goods(dark) { GoodsContent(state, {}, {}) }

@PreviewTest @Preview(widthDp = 412, heightDp = 760) @Composable fun GoodsTabLight() = GoodsTab(false, GoodsUiState(loading = false, rows = goods))
@PreviewTest @Preview(widthDp = 412, heightDp = 760) @Composable fun GoodsTabDark() = GoodsTab(true, GoodsUiState(loading = false, rows = goods))
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun GoodsTabEmptyLight() = GoodsTab(false, GoodsUiState(loading = false))
@PreviewTest @Preview(widthDp = 412, heightDp = 600) @Composable fun GoodsTabEmptyDark() = GoodsTab(true, GoodsUiState(loading = false))
