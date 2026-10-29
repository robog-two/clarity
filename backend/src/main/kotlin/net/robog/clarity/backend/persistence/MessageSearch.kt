package net.robog.clarity.backend.persistence

import kotlin.time.Instant
import net.robog.clarity.shared.noun.MessagePayload

/** What [NounReader.searchMessages] looks for: [terms] plus optional filters. */
data class MessageSearch(
    val terms: List<SearchTerm>,
    val range: OpenRange = OpenRange(),
    val channel: MessagePayload.Channel? = null,
    /** An email folder or label (see [emailFolderPattern]); null for every folder. SMS and chat are always included. */
    val folder: String? = null,
    val limit: Int = 20,
)

/** A time window either end of which may be open. */
data class OpenRange(val from: Instant? = null, val until: Instant? = null)

/** One search term: a word, or a quoted phrase of words in order. Words are lowercase letters and digits. */
data class SearchTerm(val words: List<String>) {
    /** `to_tsquery` text: each word as a prefix, a phrase's words adjacent; [weight] narrows to one tsvector weight. */
    fun tsQuery(weight: Char? = null): String = words.joinToString(" <-> ") { "$it:*${weight ?: ""}" }

    /** A `LIKE` pattern over the lowercased names and addresses. */
    fun peoplePattern(): String = "%" + words.joinToString(" ").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

/** The messages found, best first; [partial] when no message matched every term, so these match only some. */
data class MessageHits(val messages: List<Stored<MessagePayload>>, val partial: Boolean = false)

private val WORD = Regex("[\\p{L}\\p{N}]+")
private val QUOTED = Regex("\"([^\"]*)\"")
private const val MAX_TERMS = 8

/**
 * Pure: the terms of free text. Quoted parts are phrases, everything else single words
 * (`"astrology club" turecek` -> [astrology club], [turecek]). Punctuation splits words, so an
 * address searches as its parts. Duplicates are dropped; at most [MAX_TERMS].
 */
fun searchTerms(raw: String): List<SearchTerm> {
    fun words(text: String) = WORD.findAll(text.lowercase()).map { it.value }.toList()
    val phrases = QUOTED.findAll(raw).map { words(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
    val rest = words(QUOTED.replace(raw, " ")).map { listOf(it) }
    return (phrases + rest).distinct().take(MAX_TERMS).map(::SearchTerm)
}
