package net.robog.clarity.backend.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicBoolean
import net.robog.clarity.backend.agent.AgentRuntime
import net.robog.clarity.backend.agent.wireName
import net.robog.clarity.backend.config.ModelProvider
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.session.SetupStatus

/** Why `PUT /api/v1/setup/model-key` did not change anything. */
sealed interface SetupError : HttpProblem {
    data object Blank : SetupError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Bad request"
        override val detail = "apiKey must not be empty"
    }

    /** Anthropic refused the key. */
    data class Rejected(val reason: String) : SetupError {
        override val status = HttpStatusCode.UnprocessableEntity
        override val title = "Key rejected"
        override val detail = reason
    }

    /** Anthropic could not be asked; the key is neither accepted nor blamed. */
    data class Unreachable(val reason: String) : SetupError {
        override val status = HttpStatusCode.BadGateway
        override val title = "Could not check the key"
        override val detail = reason
    }

    /** The server's own secret decides; a key entered in the app would be ignored. */
    data object ManagedByServer : SetupError {
        override val status = HttpStatusCode.Conflict
        override val title = "Key is managed by the server"
        override val detail = "this instance gets its Anthropic key from its configuration (ANTHROPIC_API_KEY_FILE); remove that to use a key from the app"
    }
}

/**
 * Pure: the model key state the app shows. Valid when every provider some role uses has a key;
 * otherwise the Anthropic key decides (the one the app can enter), and a missing server-only key
 * (OpenRouter) reads as missing, named in [SetupStatus.missingKeys].
 */
fun modelKeyState(missing: Set<ModelProvider>, refused: Boolean): SetupStatus.KeyState = when {
    missing.isEmpty() -> SetupStatus.KeyState.VALID
    ModelProvider.ANTHROPIC in missing && refused -> SetupStatus.KeyState.INVALID
    else -> SetupStatus.KeyState.MISSING
}

/** Pure: connectors that announced themselves and have at least one granted noun. */
fun connectorsReady(connectors: List<ConnectorSummary>): Int =
    connectors.count { it.manifest != null && it.grants.values.any { state -> state == GrantState.GRANTED } }

/** Setup state and the model key (see ARCHITECTURE.md "The model key"). */
@Inject
@SingleIn(AppScope::class)
class SetupService(
    private val runtime: AgentRuntime,
    private val keys: ModelKeyStore,
    private val validator: ModelKeyValidator,
    private val settings: SettingsStore,
    private val constitutions: ConstitutionStore,
    private val connectors: ConnectorRegistry,
) {
    /** A key was refused and nothing works yet. */
    private val refused = AtomicBoolean(false)

    suspend fun status(): SetupStatus {
        val missing = runtime.missingKeys()
        return SetupStatus(
            modelKey = modelKeyState(missing, refused.get()),
            connectorsReady = connectorsReady(connectors.list()),
            constitutionVersion = constitutions.current()?.version,
            settings = settings.current(),
            missingKeys = missing.map { it.wireName }.sorted(),
        )
    }

    /** Validates [apiKey] with Anthropic, stores it (0600) and enables the agents at once. */
    suspend fun setModelKey(apiKey: String): Either<SetupError, SetupStatus> {
        val key = apiKey.trim()
        if (key.isEmpty()) return SetupError.Blank.left()
        if (runtime.keyFromConfig) return SetupError.ManagedByServer.left()
        return when (val check = validator.validate(key)) {
            is KeyCheck.Rejected -> {
                if (ModelProvider.ANTHROPIC !in runtime.providersWithKeys) refused.set(true)
                SetupError.Rejected(check.reason).left()
            }
            is KeyCheck.Unreachable -> SetupError.Unreachable(check.reason).left()
            KeyCheck.Valid -> {
                keys.write(key)
                runtime.installKey(key)
                refused.set(false)
                status().right()
            }
        }
    }
}
