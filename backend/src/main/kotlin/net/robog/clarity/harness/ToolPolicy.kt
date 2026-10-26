package net.robog.clarity.harness

/** How the harness treats a requested tool call. */
enum class Access { ALLOW, HIDDEN }

/**
 * Per-run rules over the tool set: which tools the model can see at all. There is no approval
 * step in the harness: anything with an effect outside Clarity is gated at its own door (the
 * action gate for domain tools, the browser wrapper for the browser), so a tool that is visible
 * is a tool that may run.
 */
fun interface ToolPolicy {
    fun accessFor(tool: Tool): Access

    companion object {
        /** Names whose tools run arbitrary code in the page or the browser; never visible, whichever server exposes them. */
        private val RAW_SCRIPT_SUFFIXES = listOf("evaluate_script", "evaluate_privileged_script", "send_bidi_command")

        /**
         * The policy of the lead and the helpers: every tool is gated where it is defined (action gate,
         * browser wrapper, sandbox without network), and raw-script tools stay hidden as a second lock
         * should a wrapper ever miss one.
         */
        val Gated = ToolPolicy { tool -> if (RAW_SCRIPT_SUFFIXES.any { tool.name.endsWith(it) }) Access.HIDDEN else Access.ALLOW }

        /** The default for a run: same as [Gated]. */
        val Default: ToolPolicy = Gated

        /** Everything is visible and runs. For tests only. */
        val AllowAll = ToolPolicy { Access.ALLOW }

        /** Only the named tools are visible; the rest behave as if they did not exist. */
        fun only(names: Set<String>, otherwise: ToolPolicy = Default) = ToolPolicy { tool ->
            if (tool.name in names) otherwise.accessFor(tool) else Access.HIDDEN
        }
    }
}
