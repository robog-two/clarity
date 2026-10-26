package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.session.SessionId

class MicControllerTest {
    private fun run(start: MicState, vararg inputs: MicInput): Pair<MicState, List<MicCommand>> {
        var s = start
        val cmds = mutableListOf<MicCommand>()
        for (i in inputs) {
            val (n, c) = micStep(s, i)
            s = n
            cmds += c
        }
        return s to cmds
    }

    @Test fun startListensAndStopTurnsOff() {
        val (s, cmds) = run(MicState(), MicInput.Start(keepOn = true), MicInput.Stop)
        assertEquals(MicState(), s)
        assertEquals(listOf(MicCommand.StartRecognizer, MicCommand.StopRecognizer), cmds)
    }

    @Test fun micStaysOnRestartsAfterEachFinalSegment() {
        val (s, cmds) = run(
            MicState(),
            MicInput.Start(keepOn = true),
            MicInput.Recognizer(SpeechEvent.Final("one")),
            MicInput.Recognizer(SpeechEvent.Final("two")),
        )
        assertEquals(MicMode.Listening, s.mode)
        assertEquals(listOf(MicCommand.StartRecognizer, MicCommand.StartRecognizer, MicCommand.StartRecognizer), cmds)
    }

    @Test fun singleShotModeStopsAfterTheFirstFinal() {
        val (s, cmds) = run(MicState(), MicInput.Start(keepOn = false), MicInput.Recognizer(SpeechEvent.Final("one")))
        assertEquals(MicState(), s)
        assertEquals(listOf(MicCommand.StartRecognizer, MicCommand.None), cmds)
    }

    @Test fun silenceRestartsWhenKeptOnButNeverLoopsOnRealErrors() {
        val (s1, c1) = run(MicState(), MicInput.Start(true), MicInput.Recognizer(SpeechEvent.Error(SpeechError.NoMatch)), MicInput.Recognizer(SpeechEvent.Error(SpeechError.Timeout)))
        assertEquals(MicMode.Listening, s1.mode)
        assertEquals(listOf(MicCommand.StartRecognizer, MicCommand.StartRecognizer, MicCommand.StartRecognizer), c1)

        for (fatal in listOf(SpeechError.NoPermission, SpeechError.Unavailable, SpeechError.Busy, SpeechError.Other)) {
            val (s2, c2) = run(MicState(), MicInput.Start(true), MicInput.Recognizer(SpeechEvent.Error(fatal)))
            assertEquals(MicState(), s2, "$fatal turns the mic off")
            assertEquals(MicCommand.StopRecognizer, c2.last())
        }
    }

    @Test fun mutePausesAndUnmuteResumes() {
        val (muted, c1) = run(MicState(), MicInput.Start(true), MicInput.Mute)
        assertEquals(MicMode.Muted, muted.mode)
        assertEquals(MicCommand.StopRecognizer, c1.last())
        // Late recogniser events while muted do nothing (no restart).
        val (still, c2) = run(muted, MicInput.Recognizer(SpeechEvent.Final("late")), MicInput.Recognizer(SpeechEvent.Error(SpeechError.NoMatch)))
        assertEquals(muted, still)
        assertEquals(listOf(MicCommand.None, MicCommand.None), c2)
        val (back, c3) = run(muted, MicInput.Unmute)
        assertEquals(MicMode.Listening, back.mode)
        assertEquals(listOf(MicCommand.StartRecognizer), c3)
    }

    @Test fun muteAndUnmuteAreIgnoredWhenOff() {
        val (s, cmds) = run(MicState(), MicInput.Mute, MicInput.Unmute)
        assertEquals(MicState(), s)
        assertEquals(listOf(MicCommand.None, MicCommand.None), cmds)
    }

    @Test fun chipFollowsTheStateAndWhetherSpeechIsHeard() {
        assertEquals(MicChipState.Off, micChipState(MicState()))
        assertEquals(MicChipState.MicOn, micChipState(MicState(MicMode.Listening)))
        assertEquals(MicChipState.Listening, micChipState(MicState(MicMode.Listening, hearing = true)))
        assertEquals(MicChipState.Muted, micChipState(MicState(MicMode.Muted)))
        val (heard, _) = micStep(MicState(MicMode.Listening), MicInput.Recognizer(SpeechEvent.Hearing(true)))
        assertEquals(MicChipState.Listening, micChipState(heard))
    }

    @Test fun errorCodesMap() {
        assertEquals(SpeechError.NoMatch, speechErrorOf(7))
        assertEquals(SpeechError.Timeout, speechErrorOf(6))
        assertEquals(SpeechError.NoPermission, speechErrorOf(9))
        assertEquals(SpeechError.Busy, speechErrorOf(8))
        assertEquals(SpeechError.Unavailable, speechErrorOf(13))
        assertEquals(SpeechError.Other, speechErrorOf(2))
    }
}

class SpeechInputMappingTest {
    @Test fun finalSegmentsBecomeVoiceInputWithAFreshClientId() = runTest {
        var n = 0
        val inputs = flowOf<SpeechEvent>(
            SpeechEvent.Hearing(true),
            SpeechEvent.Partial("my old"),
            SpeechEvent.Final("  my old assistant blocked travel time  "),
            SpeechEvent.Final("   "),
            SpeechEvent.Error(SpeechError.NoMatch),
            SpeechEvent.Final("and wrote short"),
        ).toUserInputs({ SessionId("s1") }, { "id-${n++}" }).toList()
        assertEquals(2, inputs.size)
        assertEquals("my old assistant blocked travel time", inputs[0].text)
        assertEquals(InputSource.VOICE, inputs[0].source)
        assertEquals(SessionId("s1"), inputs[0].sessionId)
        assertEquals(listOf("id-0", "id-1"), inputs.map { it.clientId.value })
    }

    @Test fun inputWithoutASessionHasNone() = runTest {
        val input = flowOf<SpeechEvent>(SpeechEvent.Final("hello")).toUserInputs({ null }, { "x" }).toList().single()
        assertEquals(null, input.sessionId)
    }

    @Test fun segmentsSeparatedByShortPausesBecomeOneMessage() {
        val j = PauseJoiner()
        assertEquals(false, j.holding)
        j.segment("I want to see a movie")
        j.segment("  ")
        assertEquals("I want to see a movie maybe", j.shown("maybe"))
        j.segment(" maybe Thursday ")
        assertEquals("I want to see a movie maybe Thursday", j.flush())
        assertEquals(null, j.flush())
        assertEquals(false, j.holding)
    }

    @Test fun joiningSpeechTrimsAndSkipsEmptyPieces() {
        assertEquals("a b", joinSpeech(" a ", "b "))
        assertEquals("b", joinSpeech("", "b"))
        assertEquals("", joinSpeech(" ", ""))
    }
}
