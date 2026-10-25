package net.robog.clarity.harness

/** How the harness treats a requested tool call. */
enum class Access { ALLOW, REQUIRE_APPROVAL, HIDDEN }

/**
 * Per-run rules over the tool set: which tools the model can see at all, and which of those may
 * execute without a human. The default is conservative: reads run, anything that writes or
 * reaches outside needs approval. (The approval round trip itself is future work; today an
 * unapproved call surfaces as [HarnessEvent.ApprovalRequired] and an error result.)
 */
fun interface ToolPolicy {
    fun accessFor(tool: Tool): Access

    companion object {
        /** READ allowed; WRITE and EXTERNAL require approval. */
        val Default = ToolPolicy { tool ->
            if (tool.effect == ToolEffect.READ) Access.ALLOW else Access.REQUIRE_APPROVAL
        }

        /** Everything runs unattended. For tests and fully trusted tool sets only. */
        val AllowAll = ToolPolicy { Access.ALLOW }

        /** Only the named tools are visible; the rest behave as if they did not exist. */
        fun only(names: Set<String>, otherwise: ToolPolicy = Default) = ToolPolicy { tool ->
            if (tool.name in names) otherwise.accessFor(tool) else Access.HIDDEN
        }
    }
}
