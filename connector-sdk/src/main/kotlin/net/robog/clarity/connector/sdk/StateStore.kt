package net.robog.clarity.connector.sdk

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ClarityJson

/**
 * Small durable key-value store for connector cursors and caches (lives on the `/data` volume).
 * Values are JSON. Meant for kilobytes, not for bulk data.
 */
interface StateStore {
    suspend fun get(key: String): JsonElement?
    suspend fun put(key: String, value: JsonElement)
    suspend fun remove(key: String)
    suspend fun keys(): Set<String>
}

/** Typed read; null when the key is absent. */
suspend inline fun <reified T> StateStore.getAs(key: String): T? =
    get(key)?.let { ClarityJson.decodeFromJsonElement<T>(it) }

/** Typed write. */
suspend inline fun <reified T> StateStore.putAs(key: String, value: T) =
    put(key, ClarityJson.encodeToJsonElement(value))

/** Non-durable [StateStore] for tests of connector code. */
class InMemoryStateStore : StateStore {
    private val mutex = Mutex()
    private var data: Map<String, JsonElement> = emptyMap()
    override suspend fun get(key: String) = mutex.withLock { data[key] }
    override suspend fun put(key: String, value: JsonElement) = mutex.withLock { data = data + (key to value) }
    override suspend fun remove(key: String) = mutex.withLock { data = data - key }
    override suspend fun keys() = mutex.withLock { data.keys }
}

/**
 * [StateStore] persisted as one JSON document, `<dir>/state.json`.
 *
 * Every mutation rewrites the document to a temp file in the same directory, fsyncs it and
 * atomically renames it over the target, so a crash leaves either the old or the new state,
 * never a torn file. A leftover temp file from a crash is ignored and overwritten.
 */
class FileStateStore(private val dir: Path, private val json: Json = ClarityJson) : StateStore {
    private val file = dir.resolve(FILE_NAME)
    private val mutex = Mutex()
    private var cache: Map<String, JsonElement>? = null

    override suspend fun get(key: String): JsonElement? = mutex.withLock { load()[key] }

    override suspend fun keys(): Set<String> = mutex.withLock { load().keys }

    override suspend fun put(key: String, value: JsonElement) = mutate { it + (key to value) }

    override suspend fun remove(key: String) = mutate { it - key }

    private suspend fun mutate(change: (Map<String, JsonElement>) -> Map<String, JsonElement>) =
        mutex.withLock {
            val next = change(load())
            withContext(Dispatchers.IO) { write(next) }
            cache = next // only after the write succeeded
        }

    private suspend fun load(): Map<String, JsonElement> =
        cache ?: withContext(Dispatchers.IO) {
            if (file.exists()) json.parseToJsonElement(file.readText()).let { it as JsonObject }.toMap() else emptyMap()
        }.also { cache = it }

    private fun write(data: Map<String, JsonElement>) {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "state-", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), JsonObject(data)))
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object { const val FILE_NAME = "state.json" }
}
