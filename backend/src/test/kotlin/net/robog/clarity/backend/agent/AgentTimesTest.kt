package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import net.robog.clarity.backend.governance.NotifyDecision
import net.robog.clarity.backend.governance.localTimesNote
import net.robog.clarity.shared.governance.ProposedAction

class AgentTimesTest : FunSpec({
    val newYork = TimeZone.of("America/New_York")
    fun at(s: String) = Instant.parse(s)

    test("readToolTime: local, offset, Z, and plain dates where a day is enough") {
        readToolTime("at", "2026-10-26T19:00", newYork).getOrNull() shouldBe at("2026-10-26T23:00:00Z")
        readToolTime("at", "2026-10-26T19:00:00Z", newYork).getOrNull() shouldBe at("2026-10-26T19:00:00Z")
        readToolTime("from", "2026-10-26", newYork, DayAs.START).getOrNull() shouldBe at("2026-10-26T04:00:00Z")
        readToolTime("until", "2026-10-26", newYork, DayAs.END).getOrNull() shouldBe at("2026-10-27T04:00:00Z")
        readToolTime("at", "2026-10-26", newYork).leftOrNull()!! shouldContain "time of day"
        readToolTime("at", null, newYork).leftOrNull() shouldBe "`at` is required"
        readToolTime("at", "soon", newYork).leftOrNull()!! shouldContain "not a time"
    }

    test("a stored record's instants are restated locally; all-day events become dates; other strings stay") {
        val record = Json.parseToJsonElement(
            """{"sentAt":"2026-10-27T01:30:00Z","subject":"2026-10-27T01:30:00Z is when","n":3,""" +
                """"nested":[{"start":"2026-10-26T00:00:00Z","end":"2026-10-27T00:00:00Z","allDay":true}]}""",
        )
        localizeTimes(record, newYork).toString() shouldBe
            """{"sentAt":"2026-10-26T21:30-04:00 (Mon)","subject":"2026-10-27T01:30:00Z is when","n":3,""" +
            """"nested":[{"start":"2026-10-26 (Mon)","end":"2026-10-26 (Mon)","allDay":true}]}"""
    }

    test("helpers are told the current time on the person's clock") {
        helperPrompt("book it", at("2026-10-27T01:30:00Z"), newYork) shouldContain
            "Current time: 2026-10-26T21:30-04:00 (Mon), America/New_York"
    }

    test("send_to_user says when a held notification goes out in local time") {
        NotifyDecision.HoldUntil(at("2026-10-27T01:00:00Z")).describe(newYork) shouldContain "until 2026-10-26T21:00-04:00 (Mon)"
        NotifyDecision.EveningBatch(at("2026-10-26T23:00:00Z")).describe(newYork) shouldContain "at 2026-10-26T19:00-04:00 (Mon)"
    }

    test("the guard model is told the zone and the action's times on that clock") {
        val event = ProposedAction.CreateEvent("k", "Hold", title = "Focus", start = at("2026-10-26T23:00:00Z"), end = at("2026-10-27T00:00:00Z"))
        val note = localTimesNote(event, newYork)
        note shouldContain "zone=\"America/New_York\""
        note shouldContain "start 2026-10-26T19:00-04:00 (Mon), end 2026-10-26T20:00-04:00 (Mon)"
        localTimesNote(ProposedAction.ReadDocument("k", "read", net.robog.clarity.shared.EntityId("d")), newYork) shouldNotContain "read there as"
    }
})
