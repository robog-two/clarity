package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.sessions.PlanItemState
import net.robog.clarity.backend.sessions.PlanItemStore
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card

/**
 * The one grammar of a choices card's `ref`: absent, or exactly `outing:<uuid>` or `plan:<uuid>` with a strict lower-case
 * 8-4-4-4-12 uuid, no spaces and no other prefix. The post gate ([CardRefGate], the outing gate), the tap note
 * ([answeringNote]) and the outing ref parser all read it here and nowhere else.
 */
object CardRefs {
    enum class Kind(val prefix: String) { OUTING("outing"), PLAN("plan") }

    data class Parsed(val kind: Kind, val id: String) {
        val ref: String get() = "${kind.prefix}:$id"
    }

    const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

    private val GRAMMAR = Regex("^(outing|plan):($UUID_PATTERN)$")

    /** Pure: [ref] read exactly, or null when it is absent or anything else. */
    fun parse(ref: String?): Parsed? {
        val m = ref?.let(GRAMMAR::matchEntire) ?: return null
        return Parsed(Kind.entries.first { it.prefix == m.groupValues[1] }, m.groupValues[2])
    }

    /** Pure: why [ref] cannot be on a posted card, or null when it is absent or well formed. */
    fun problem(ref: String?): String? =
        if (ref == null || parse(ref) != null) null
        else "A choices card's ref is absent or exactly \"outing:<id>\" or \"plan:<id>\" (the id in lower case, as given). \"${ref.take(60)}\" is neither."
}

/**
 * Wraps `send_to_user` so a choices ref is valid before anything is posted: the grammar above, and a `plan:<id>` must
 * name a plan item that is open. (An `outing:` ref gets its further checks from the outing gate.)
 */
@Inject
@SingleIn(AppScope::class)
class CardRefGate(private val planItems: PlanItemStore) {
    fun wrap(send: Tool): Tool = object : Tool by send {
        override suspend fun invoke(input: JsonObject): ToolResult {
            val cards = input["cards"]?.let { runCatching { ClarityJson.decodeFromJsonElement(ListSerializer(Card.serializer()), it) }.getOrNull() }
                ?: return send.invoke(input)
            for (card in cards.filterIsInstance<Card.Choices>()) {
                CardRefs.problem(card.ref)?.let { return ToolResult.Error(it) }
                val parsed = CardRefs.parse(card.ref) ?: continue
                if (parsed.kind == CardRefs.Kind.PLAN && planItems.get(parsed.id)?.state != PlanItemState.OPEN) {
                    return ToolResult.Error("No open plan item has id ${parsed.id}. A plan check-in's ref is \"plan:<the plan item id>\" of an item on the plan.")
                }
            }
            return send.invoke(input)
        }
    }
}
