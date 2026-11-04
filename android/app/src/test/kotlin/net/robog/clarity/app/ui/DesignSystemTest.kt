package net.robog.clarity.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screens look and move the same because they are built the same way, and this reads the source tree
 * to keep it that way (like SpacingBudgetTest). A screen that opts in by hand is how motion drifted
 * apart once, so the rules are structural: one scaffold, one motion file, one grouped-card widget.
 *
 * Each failure lists file:line and says what to use instead. An exception goes in an allowlist below,
 * with the reason on the same line, never as a skipped check.
 */
class DesignSystemTest {
    private val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.isDirectory }.resolve("net/robog/clarity/app")

    private class Source(val path: String, val lines: List<String>) {
        /** The code with comments and import lines blanked, so words in prose are not flagged. */
        val code: String = lines.joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line.replace(Regex("""//.*$"""), "")
        }
        fun lineOf(offset: Int) = code.take(offset).count { it == '\n' } + 1
    }

    private val sources: List<Source> by lazy {
        root.walkTopDown().filter { it.extension == "kt" }.map { f ->
            Source(f.relativeTo(root).path.replace(File.separatorChar, '/'), f.readLines())
        }.toList()
    }

    // ---- Motion ----

    /** Compose enter and exit APIs and hand-made timing. Only Motion.kt may use these. */
    private val motionTokens = Regex(
        """\b(AnimatedContent|AnimatedVisibility|Crossfade|fadeIn|fadeOut|slideIn(?:Horizontally|Vertically)|slideOut(?:Horizontally|Vertically)|scaleIn|scaleOut|""" +
            """expandVertically|shrinkVertically|tween|keyframes|animateContentSize|animate\w*AsState|Animatable|""" +
            """rememberInfiniteTransition|translationX|translationY)\b""",
    )

    private fun category(token: String) = when {
        token.startsWith("animate") && token.endsWith("AsState") -> "animateAsState"
        token == "rememberInfiniteTransition" -> "infinite"
        token.startsWith("translation") -> "translation"
        else -> token
    }

    /**
     * Files that may keep their own animation, because a finger or a loop drives it, and which kinds.
     * Anything not listed here (a fade, a slide, a scale in or out) goes through Motion.kt.
     */
    private val motionAllowlist: Map<String, Pair<Set<String>, String>> = mapOf(
        "ui/components/Motion.kt" to (setOf("*") to "the one motion vocabulary"),
        "ui/call/SlideToAnswer.kt" to (setOf("Animatable", "animateAsState", "tween", "infinite") to "the thumb follows the finger, then settles; the knob turns in a loop"),
        "ui/components/SlideToConfirm.kt" to (setOf("Animatable") to "the thumb follows the finger, then settles"),
        "ui/components/Components.kt" to (setOf("animateAsState") to "the button's label and spinner alpha while busy, a press-style state"),
        "ui/today/TodayScreen.kt" to (setOf("animateAsState", "tween", "infinite") to "held badge spins in a loop; fold chevron turn and held card press squish"),
        "ui/path/PathNodes.kt" to (setOf("Animatable", "animateAsState", "tween", "infinite") to "path node pulse and turn loops and its fill colour"),
        "ui/call/CallScreens.kt" to (setOf("keyframes", "infinite") to "the ringing bell swings in an ambient loop"),
        "ui/path/ZoomableFrame.kt" to (setOf("translation") to "pinch and drag pan the full-screen image under the finger"),
    )

    @Test
    fun `motion lives in Motion kt`() {
        val offenders = sources.flatMap { s ->
            val allowed = motionAllowlist[s.path]?.first.orEmpty()
            if ("*" in allowed) return@flatMap emptyList()
            motionTokens.findAll(s.code).filter { category(it.value) !in allowed }.map {
                "${s.path}:${s.lineOf(it.range.first)} uses ${it.value}"
            }.toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "Motion comes from Motion.kt. Use ClarityScreen (it staggers its children), PhaseSwap for a swap in place, " +
                "springEnter/springExit for something opening, arrivingItem() for a lazy item:\n" + offenders.joinToString("\n"),
        )
    }

    // ---- Layout ----

    private val layoutTokens = Regex("""\b(verticalScroll\(|LazyColumn\(|Scaffold\(|statusBarsPadding\(\))""")

    /** Files that may lay out a screen themselves, by token. */
    private val layoutAllowlist: Map<String, Pair<Set<String>, String>> = mapOf(
        "ui/components/ScreenLayout.kt" to (setOf("*") to "the one screen layout"),
        "ui/components/Chat.kt" to (setOf("*") to "the chat transcript anchors to the bottom and has its own list"),
        "ui/main/MainScaffold.kt" to (setOf("Scaffold(") to "the tab bar and the snackbars"),
        "ui/cards/MessageView.kt" to (setOf("verticalScroll(") to "a bottom sheet scrolls its attachment inside itself"),
        "ui/path/ZoomableFrame.kt" to (setOf("statusBarsPadding()") to "a full-screen image dialog keeps its close button clear of the status bar"),
    )

    @Test
    fun `screens are laid out by ScreenLayout kt`() {
        val offenders = sources.flatMap { s ->
            val allowed = layoutAllowlist[s.path]?.first.orEmpty()
            if ("*" in allowed) return@flatMap emptyList()
            layoutTokens.findAll(s.code).filter { it.value !in allowed }.map {
                "${s.path}:${s.lineOf(it.range.first)} uses ${it.value}"
            }.toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "Use ClarityScreen (scrolling body) or ClarityListScreen (long list) instead of laying a screen out by hand:\n" + offenders.joinToString("\n"),
        )
    }

    // ---- Screens ----

    private val scaffolds = Regex("""\b(ClarityScreen|ClarityListScreen|StepScaffold|ChatLayout)\s*\(""")

    /** Names that host screens and are not screens (they only choose which one shows). */
    private val hosts = mapOf(
        "OnboardingFlow" to "chooses the onboarding step",
        "MainScaffold" to "the tab bar around the tab screens",
    )

    private class Fn(val source: Source, val name: String, val body: String, val line: Int)

    private val functions: List<Fn> by lazy {
        val decl = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)\s*\(""")
        sources.flatMap { s ->
            decl.findAll(s.code).mapNotNull { m ->
                val before = s.code.substring(0, m.range.first)
                val start = maxOf(before.lastIndexOf("\n}"), before.lastIndexOf("\n\n"), 0)
                if ("@Composable" !in before.substring(start)) return@mapNotNull null
                val paramsEnd = matching(s.code, m.range.last, '(', ')')
                var i = paramsEnd
                while (i < s.code.length && s.code[i] != '{' && s.code[i] != '=') i++
                val body = if (i >= s.code.length) "" else if (s.code[i] == '{') {
                    s.code.substring(i, matching(s.code, i, '{', '}'))
                } else {
                    val stop = Regex("""\n\n|\n\}""").find(s.code, i)?.range?.first ?: s.code.length
                    s.code.substring(i, stop)
                }
                Fn(s, m.groupValues[1], body, s.lineOf(m.range.first))
            }.toList()
        }
    }

    /** The index just after the bracket that closes the one at [open]. */
    private fun matching(text: String, open: Int, up: Char, down: Char): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == up) depth++
            if (text[i] == down && --depth == 0) return i + 1
            i++
        }
        return text.length
    }

    private fun usesScaffold(fn: Fn): Boolean =
        scaffolds.containsMatchIn(fn.body) ||
            Regex("""\b(?!${fn.name}\b)[A-Z]\w*(Screen|Page|Content)\s*\(""").containsMatchIn(fn.body)

    @Test
    fun `every screen and page uses a scaffold`() {
        val screens = functions.filter {
            (it.name.endsWith("Screen") || it.name.endsWith("Page")) && it.name !in hosts && it.source.path != "ui/components/ScreenLayout.kt"
        }
        val offenders = screens.filterNot(::usesScaffold).map { "${it.source.path}:${it.line} ${it.name} does not call a scaffold" }.toMutableList()

        // What a nav entry renders must be a screen, or a host that is named above.
        val nav = sources.first { it.path == "nav/ClarityNavHost.kt" }
        val rendered = Regex("""\b([A-Z]\w*(?:Screen|Page|Flow|Scaffold))\s*\(""")
        nav.code.split(Regex("""(?=entry<)""")).drop(1).forEach { block ->
            val names = rendered.findAll(block).map { it.groupValues[1] }.toSet()
            names.filter { it !in hosts }.forEach { name ->
                val fns = functions.filter { it.name == name }
                if (fns.isEmpty() || fns.none(::usesScaffold)) offenders += "nav/ClarityNavHost.kt renders $name, which is not built on a scaffold"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Build every screen on ClarityScreen, ClarityListScreen or StepScaffold (or hand it to a function that does):\n" + offenders.joinToString("\n"),
        )
    }

    // ---- Illustrations ----

    private val drawingCalls = Regex("""\b(Drawing|painterResource)\(""")

    /** Files where a drawing is part of a component, not a screen's illustration. */
    private val drawingAllowlist = mapOf(
        "ui/components/Drawing.kt" to "the drawing widget itself",
        "ui/call/CallScreens.kt" to "the ringing bell is the incoming call's animated centrepiece",
        "ui/today/TodayScreen.kt" to "the empty-plan card's drawing and the running task's solid, which are components",
    )

    @Test
    fun `screen illustrations sit in the art slot`() {
        val offenders = sources.filter { it.path !in drawingAllowlist }.flatMap { s ->
            drawingCalls.findAll(s.code).filter { call ->
                // Fine only inside the braces of an `art = ...` argument.
                val from = s.code.lastIndexOf("art =", call.range.first)
                from < 0 || s.code.substring(from, call.range.first).let { it.count { c -> c == '{' } - it.count { c -> c == '}' } } <= 0
            }.map { "${s.path}:${s.lineOf(it.range.first)} draws an illustration outside the art slot" }.toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "A screen's illustration goes in ClarityScreen's art = { } slot, at the end of the title row, never in the body:\n" + offenders.joinToString("\n"),
        )
    }

    // ---- Cards and lines ----

    private val cardTokens = Regex("""\b(Card|ElevatedCard|OutlinedCard|HorizontalDivider|Divider)\(|\bshadowElevation\s*=\s*(?!0\.dp\b|0f\b)""")
    private val cardAllowlist = mapOf(
        "ui/components/CardGroup.kt" to "the grouped card widget itself",
        "ui/goods/MarkdownView.kt" to "the markdown renderer draws a horizontal rule",
    )

    @Test
    fun `grouped content uses CardGroup and has no lines`() {
        val offenders = sources.filter { it.path !in cardAllowlist }.flatMap { s ->
            cardTokens.findAll(s.code).map { "${s.path}:${s.lineOf(it.range.first)} uses ${it.value.trim()}" }.toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "Use CardGroup or SegmentCard for grouped content, with no shadow and no divider line:\n" + offenders.joinToString("\n"),
        )
    }
}
