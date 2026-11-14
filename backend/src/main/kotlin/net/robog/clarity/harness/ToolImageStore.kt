package net.robog.clarity.harness

import java.util.concurrent.ConcurrentHashMap

/**
 * Where the bytes of the pictures tools showed are kept, by run and tool call. The journal holds only a
 * reference ([Block.Image]); the bytes are read when a request is built, and may be gone by then (they expire).
 */
interface ToolImageStore {
    suspend fun save(runId: String, toolUseId: String, image: ToolImage)

    suspend fun load(runId: String, toolUseId: String): ToolImage?

    companion object {
        /** Keeps nothing: pictures are never shown. */
        val NONE: ToolImageStore = object : ToolImageStore {
            override suspend fun save(runId: String, toolUseId: String, image: ToolImage) = Unit
            override suspend fun load(runId: String, toolUseId: String): ToolImage? = null
        }
    }
}

class InMemoryToolImageStore : ToolImageStore {
    private val images = ConcurrentHashMap<Pair<String, String>, ToolImage>()

    override suspend fun save(runId: String, toolUseId: String, image: ToolImage) {
        images[runId to toolUseId] = image
    }

    override suspend fun load(runId: String, toolUseId: String): ToolImage? = images[runId to toolUseId]

    fun forget(runId: String, toolUseId: String) { images.remove(runId to toolUseId) }
}
