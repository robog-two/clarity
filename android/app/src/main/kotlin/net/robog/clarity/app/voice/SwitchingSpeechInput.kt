package net.robog.clarity.app.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The [SpeechInput] screens get: the app's own two-pass models when they are downloaded and run on
 * this phone, otherwise the phone's built-in recogniser. Both share one [SpeechBus], so screens see one
 * event flow and one mic state whichever is listening. The choice is made at each start.
 */
class SwitchingSpeechInput(
    bus: SpeechBus,
    private val own: SpeechInput,
    private val builtIn: SpeechInput,
    /** Models present, not switched off in Settings, and the engine has not failed on this phone. */
    private val useOwn: () -> Boolean,
) : SpeechInput {
    override val events: Flow<SpeechEvent> = bus.events
    override val mic: StateFlow<MicState> = bus.mic

    @Volatile private var active: SpeechInput? = null

    override fun start(keepOn: Boolean) {
        val target = if (useOwn()) own else builtIn
        val previous = active
        // Only one listens: the other is stopped first if it was still on (it shares the mic state).
        if (previous != null && previous !== target && mic.value.mode != MicMode.Off) previous.stop()
        active = target
        target.start(keepOn)
    }

    /** The own engine could not open: carry on with the built-in recogniser. */
    fun fallBack(keepOn: Boolean) {
        active = builtIn
        builtIn.start(keepOn)
    }

    override fun stop() { active?.stop() }
    override fun mute() { active?.mute() }
    override fun unmute() { active?.unmute() }

    override fun release() {
        own.release()
        builtIn.release()
    }
}
