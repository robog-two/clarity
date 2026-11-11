package net.robog.clarity.shared.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import net.robog.clarity.shared.ClarityJson

class McpWireTest {
    private fun tool(mode: String) = """{"servers":[{"id":"a","label":"A","state":"idle","tools":[{"name":"t","mode":"$mode"},{"name":"u","mode":"ask"}]}]}"""

    private fun modes(mode: String) =
        ClarityJson.decodeFromString<McpServerList>(tool(mode)).servers.single().tools.map { it.mode }

    @Test
    fun threeModesUseTheirWireNames() {
        assertEquals(listOf(McpToolMode.ASK, McpToolMode.ASK), modes("ask"))
        assertEquals(McpToolMode.AUTO, modes("auto").first())
        assertEquals(McpToolMode.ALWAYS_OK, modes("always_ok").first())
        assertEquals("""{"mode":"always_ok"}""", ClarityJson.encodeToString(McpToolPolicyUpdate(McpToolMode.ALWAYS_OK)))
        assertEquals("""{"mode":"ask"}""", ClarityJson.encodeToString(McpToolPolicyUpdate(McpToolMode.ASK)))
    }

    @Test
    fun anUnknownModeFromANewerServerReadsAsAutoAndDoesNotFailTheList() {
        assertEquals(listOf(McpToolMode.AUTO, McpToolMode.ASK), modes("sometimes"))
    }

    @Test
    fun theRetiredOffModeReadsAsAsk() {
        assertEquals(McpToolMode.ASK, modes("off").first())
        assertEquals(McpToolMode.ASK, ClarityJson.decodeFromString<McpToolPolicyUpdate>("""{"mode":"off"}""").mode)
        assertEquals(null, ClarityJson.decodeFromString<McpToolPolicyUpdate>("""{"mode":null}""").mode)
    }
}
