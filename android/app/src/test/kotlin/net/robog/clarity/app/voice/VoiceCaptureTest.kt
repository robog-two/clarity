package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import net.robog.clarity.app.testutil.ScreenSpeech

class VoiceCaptureTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun onlyTheScreenThatTurnedTheMicOnHearsIt() {
        val speech = ScreenSpeech()
        val chat = mutableListOf<String>()
        val amend = mutableListOf<String>()
        val under = VoiceCapture(speech) { chat += it }.also { it.attach(scope) }
        val top = VoiceCapture(speech) { amend += it }.also { it.attach(scope) }

        under.start(keepOn = true)
        speech.flow.tryEmit(SpeechEvent.Final("to the chat"))
        top.start(keepOn = true) // the rule-change talk opens over the chat
        speech.flow.tryEmit(SpeechEvent.Partial("creating"))
        speech.flow.tryEmit(SpeechEvent.Final("creating calendar events is okay"))

        assertEquals(listOf("to the chat"), chat)
        assertEquals(listOf("creating calendar events is okay"), amend)
        assertEquals(null, under.live.value, "the screen underneath shows no live words either")
        scope.cancel()
    }

    @Test fun theLastSegmentAfterStoppingStillGoesToTheScreenThatStopped() {
        val speech = ScreenSpeech()
        val heard = mutableListOf<String>()
        val capture = VoiceCapture(speech) { heard += it }.also { it.attach(scope) }
        capture.start(keepOn = false)
        capture.stop()
        speech.flow.tryEmit(SpeechEvent.Final("held segment"))
        assertEquals(listOf("held segment"), heard)
        scope.cancel()
    }
}
