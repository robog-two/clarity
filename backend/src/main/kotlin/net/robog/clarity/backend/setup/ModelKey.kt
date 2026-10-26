package net.robog.clarity.backend.setup

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.agent.AgentRuntime
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.lifecycle.StartupTask

private val log = KotlinLogging.logger {}

/** The result of asking Anthropic whether a key works. */
sealed interface KeyCheck {
    data object Valid : KeyCheck

    /** Anthropic answered and refused the key (401/403). */
    data class Rejected(val reason: String) : KeyCheck

    /** No verdict: the API could not be reached or failed (so the key is not blamed). */
    data class Unreachable(val reason: String) : KeyCheck
}

/** Validates a key with one cheap call. A seam so tests use a fake endpoint. */
fun interface ModelKeyValidator {
    suspend fun validate(apiKey: String): KeyCheck
}

/** One Models API call through the SDK (`GET /v1/models?limit=1`); the key is never logged. */
@Inject
@ContributesBinding(AppScope::class)
class AnthropicKeyValidator(private val config: AgentConfig) : ModelKeyValidator {
    override suspend fun validate(apiKey: String): KeyCheck {
        val client = net.robog.clarity.backend.agent.anthropicClientBuilder(apiKey, config).maxRetries(0).build()
        return try {
            runInterruptible(Dispatchers.IO) { client.models().list(com.anthropic.models.models.ModelListParams.builder().limit(1).build()) }
            KeyCheck.Valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            KeyCheck.Rejected("Anthropic does not accept this key")
        } catch (e: PermissionDeniedException) {
            KeyCheck.Rejected("this key is not allowed to use the API")
        } catch (e: Exception) {
            log.warn { "could not validate the model key: ${e::class.simpleName}" }
            KeyCheck.Unreachable("could not reach Anthropic (${e::class.simpleName})")
        } finally {
            client.close()
        }
    }
}

/** The key entered in setup, kept in `${dataDir}/anthropic-api-key` (0600). It never leaves the server. */
@Inject
@SingleIn(AppScope::class)
class ModelKeyStore(config: AppConfig) {
    private val file: Path = config.dataDir.resolve(FILE_NAME)

    suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching { Files.readString(file).trim().takeIf(String::isNotEmpty) }.getOrNull()
    }

    suspend fun write(key: String) = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parent ?: Path.of("."))
        val temp = Files.createTempFile(file.parent ?: Path.of("."), ".anthropic-api-key", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.writeString(temp, key)
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object { const val FILE_NAME = "anthropic-api-key" }
}

/** At boot: use the stored key (unless a configured one exists), so agents start without the app. */
@Inject
@ContributesIntoSet(AppScope::class)
class StoredModelKeyLoader(private val keys: ModelKeyStore, private val runtime: AgentRuntime) : StartupTask {
    override val name = "load-model-key"

    override suspend fun run() {
        if (runtime.enabled) return
        keys.read()?.let { runtime.installKey(it) }
    }
}
