package net.robog.clarity.shared.query

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.noun.Noun

/**
 * A small, serializable predicate language over noun payload fields, used by agent-defined
 * triggers ("if a message from X mentions Y arrives, dispatch an agent"). Evaluated in-process
 * on freshly ingested records; deliberately not SQL so it can't touch anything else.
 *
 * Field paths are payload JSON paths, e.g. `sender.value`, `subject`, `attendees.value`.
 */
@Serializable
sealed interface RecordQuery {
    @Serializable @SerialName("all")
    data class All(val of: List<RecordQuery>) : RecordQuery

    @Serializable @SerialName("any")
    data class AnyOf(val of: List<RecordQuery>) : RecordQuery

    @Serializable @SerialName("not")
    data class Not(val query: RecordQuery) : RecordQuery

    @Serializable @SerialName("equals")
    data class Equals(val field: String, val value: String, val ignoreCase: Boolean = true) : RecordQuery

    @Serializable @SerialName("contains")
    data class Contains(val field: String, val value: String, val ignoreCase: Boolean = true) : RecordQuery

    @Serializable @SerialName("matches")
    data class Matches(val field: String, val regex: String) : RecordQuery
}

/** An agent-defined trigger: when a new [noun] record matches [query], run an agent with [instruction]. */
@Serializable
data class TriggerSpec(
    val name: String,
    val noun: Noun,
    val query: RecordQuery,
    val instruction: String,
    val enabled: Boolean = true,
)
