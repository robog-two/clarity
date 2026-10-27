package net.robog.clarity.backend.setup

import arrow.core.Either
import arrow.core.left
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.HttpStatusCode
import net.robog.clarity.backend.agent.AgentRuntime
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.session.SetupStatus

/** Why `PUT /api/v1/setup/model-key` did not change anything. */
sealed interface SetupError : HttpProblem {
    /** The model key comes from the server's configuration; one entered in the app is not used. */
    data object ManagedByServer : SetupError {
        override val status = HttpStatusCode.Conflict
        override val title = "Key is managed by the server"
        override val detail = "this server gets its OpenRouter key from its configuration (OPENROUTER_API_KEY_FILE); it can't be set from the app"
    }
}

/** Pure: the model key state the app shows: valid when no provider is missing a key. */
fun modelKeyState(missing: Set<String>): SetupStatus.KeyState =
    if (missing.isEmpty()) SetupStatus.KeyState.VALID else SetupStatus.KeyState.MISSING

/** Pure: connectors that announced themselves and have at least one granted noun. */
fun connectorsReady(connectors: List<ConnectorSummary>): Int =
    connectors.count { it.manifest != null && it.grants.values.any { state -> state == GrantState.GRANTED } }

/** Setup state (see ARCHITECTURE.md "The model key"). */
@Inject
@SingleIn(AppScope::class)
class SetupService(
    private val runtime: AgentRuntime,
    private val settings: SettingsStore,
    private val constitutions: ConstitutionStore,
    private val connectors: ConnectorRegistry,
) {
    suspend fun status(): SetupStatus {
        val missing = runtime.missingKeys
        return SetupStatus(
            modelKey = modelKeyState(missing),
            connectorsReady = connectorsReady(connectors.list()),
            constitutionVersion = constitutions.current()?.version,
            settings = settings.current(),
            missingKeys = missing.sorted(),
        )
    }

    /**
     * Older apps offer to enter an Anthropic key here. The model key (OpenRouter) is part of the
     * server's configuration, so this always refuses; nothing is stored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setModelKey(apiKey: String): Either<SetupError, SetupStatus> = SetupError.ManagedByServer.left()
}
