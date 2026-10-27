package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.shared.time.LocalTimes

/*
 * Times at the agents' edge: tool inputs read and tool outputs written on the person's own clock
 * (their home zone), with [LocalTimes]. Storage stays in instants.
 */

/** For tool schemas: how a time input is read. */
const val LOCAL_TIME_HINT =
    "The person's local time (their home zone), e.g. 2026-10-26T19:00; an offset (-04:00, Z) is optional and only needed for another zone."

/** What a plain date means where a tool takes a moment. */
enum class DayAs {
    /** A plain date is not enough: a time of day is needed. */
    REJECT,

    /** The start of that day (00:00 local). */
    START,

    /** The end of that day (00:00 local the next day), for an exclusive range end that includes the day. */
    END,
}

/** Pure: a tool's time input [raw] for [label], read on the clock of [zone]; Left is the message for the model. */
fun readToolTime(label: String, raw: String?, zone: TimeZone, dayAs: DayAs = DayAs.REJECT): Either<String, Instant> {
    if (raw.isNullOrBlank()) return "`$label` is required".left()
    return when (val p = LocalTimes.parse(raw, zone)) {
        is LocalTimes.Parsed.At -> p.instant.right()
        is LocalTimes.Parsed.Day -> when (dayAs) {
            DayAs.START -> p.start.right()
            DayAs.END -> p.end.right()
            DayAs.REJECT -> "`$label` needs a time of day, not just a date: e.g. ${p.date}T09:00 (local time, ${zone.id}).".left()
        }
        null -> ("`$label` is not a time: \"$raw\". Use the person's local time like 2026-10-26T19:00 (${zone.id}), " +
            "or a date like 2026-10-26 where a day is enough; an offset like -04:00 or Z is optional.").left()
    }
}

/** Pure: [moment] as the agent sees it. */
fun agentTime(moment: Instant, zone: TimeZone): String = LocalTimes.format(moment, zone)

private fun utcInstant(text: String): Instant? = LocalTimes.parseMoment(text, kotlinx.datetime.TimeZone.UTC)

private val INSTANT_TEXT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?Z$""")

/**
 * Pure: [json] (a stored record) with every UTC instant restated on the clock of [zone]. An object
 * with `"allDay": true` shows its `start` and `end` as the days it covers (end inclusive).
 */
fun localizeTimes(json: JsonElement, zone: TimeZone): JsonElement = when (json) {
    is JsonObject -> {
        val allDay = (json["allDay"] as? JsonPrimitive)?.booleanOrNull == true
        val start = (json["start"] as? JsonPrimitive)?.contentOrNull?.takeIf { allDay }?.let(::utcInstant)
        val end = (json["end"] as? JsonPrimitive)?.contentOrNull?.takeIf { allDay }?.let(::utcInstant)
        val days = if (start != null && end != null) LocalTimes.allDayDates(start, end) else null
        JsonObject(
            json.mapValues { (key, value) ->
                when {
                    days != null && key == "start" -> JsonPrimitive(LocalTimes.formatDate(days.first))
                    days != null && key == "end" -> JsonPrimitive(LocalTimes.formatDate(days.second))
                    else -> localizeTimes(value, zone)
                }
            },
        )
    }
    is JsonArray -> JsonArray(json.map { localizeTimes(it, zone) })
    is JsonPrimitive -> {
        val text = if (json.isString) json.content else null
        if (text != null && INSTANT_TEXT.matches(text)) {
            utcInstant(text)?.let { JsonPrimitive(agentTime(it, zone)) } ?: json
        } else {
            json
        }
    }
}
