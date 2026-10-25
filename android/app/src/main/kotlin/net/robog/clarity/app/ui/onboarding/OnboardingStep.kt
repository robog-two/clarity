package net.robog.clarity.app.ui.onboarding

import net.robog.clarity.shared.session.SetupStatus

/** The screens of the onboarding flow, in order (the connector form is a detour from [Connectors]). */
enum class OnboardingStep {
    Welcome, Server, Model, Connectors, ConnectorSetup, ValuesIntro, Values, Constitution, Schedule, Done;

    /** The step "continue" leads to. */
    fun next(): OnboardingStep = when (this) {
        ConnectorSetup -> Connectors
        Connectors -> ValuesIntro
        Done -> Done
        else -> entries[ordinal + 1]
    }

    /** The step "← back" leads to; null when there is nothing before it. */
    fun previous(): OnboardingStep? = when (this) {
        Welcome, Done -> null
        ConnectorSetup -> Connectors
        ValuesIntro -> Connectors
        Values -> ValuesIntro
        Constitution -> ValuesIntro
        Schedule -> Constitution
        else -> entries[ordinal - 1]
    }

    /** How many of the four bar segments are filled (setup, values, review, morning call). */
    val segments: Int
        get() = when (this) {
            Welcome, Done -> 0
            Server, Model, Connectors, ConnectorSetup -> 1
            ValuesIntro, Values -> 2
            Constitution -> 3
            Schedule -> 4
        }

    /** "step 2 of 4 · values" (null on the welcome and done screens). */
    val kicker: String?
        get() = when (this) {
            Welcome, Done -> null
            Server -> "step 1 of 4 · server"
            Model -> "step 1 of 4 · model"
            Connectors -> "step 1 of 4 · connectors"
            ConnectorSetup -> "step 1 of 4 · connectors"
            ValuesIntro -> "step 2 of 4 · values"
            Values -> "step 2 of 4 · values"
            Constitution -> "step 3 of 4 · your constitution"
            Schedule -> "step 4 of 4 · morning call"
        }
}

/**
 * Where onboarding resumes. A device that has not enrolled starts at the welcome screen; an enrolled
 * one continues at the first thing the server says is still missing. [status] is null when the
 * server does not report setup status yet.
 */
fun initialStep(enrolled: Boolean, status: SetupStatus?): OnboardingStep = when {
    !enrolled -> OnboardingStep.Welcome
    status == null -> OnboardingStep.Model
    status.modelKey != SetupStatus.KeyState.VALID -> OnboardingStep.Model
    status.connectorsReady == 0 -> OnboardingStep.Connectors
    status.constitutionVersion == null -> OnboardingStep.ValuesIntro
    else -> OnboardingStep.Schedule
}

/** True when the server considers setup finished: a valid key, a connector, and a constitution. */
fun isServerSetupComplete(status: SetupStatus?): Boolean =
    status != null &&
        status.modelKey == SetupStatus.KeyState.VALID &&
        status.connectorsReady > 0 &&
        status.constitutionVersion != null
