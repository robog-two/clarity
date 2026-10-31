package net.robog.clarity.mcphost

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ClarityJson

/** What an install left behind: for which [installKey], and the argv that starts the server. */
@Serializable
data class Installed(val installKey: String, val command: List<String>)

/** A server as last seen in a config, so `GET /servers` can answer before the first config poll after a restart. */
@Serializable
data class Known(
    val id: String,
    val label: String,
    val description: String = "",
    val source: String = "",
    val allowHosts: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/**
 * The cached `tools/list` of a server (raw MCP tool objects), when it was taken, and the server's
 * answer to `initialize`, so a client's `initialize` and `tools/list` need not start it.
 */
@Serializable
data class Listing(val runKey: String, val listedAt: String, val tools: JsonArray, val initialize: JsonObject? = null)

@Serializable
data class HostStateData(
    val uids: Map<String, Int> = emptyMap(),
    val nextUid: Int = FIRST_UID,
    val installed: Map<String, Installed> = emptyMap(),
    val known: List<Known> = emptyList(),
) {
    companion object {
        /** Server uids start well above the image's users (the egress proxy's group is 10001). */
        const val FIRST_UID = 20000
        const val LAST_UID = 65000
    }
}

/**
 * The supervisor's own durable state under its private directory (`/data/supervisor`, 0700 root):
 * uids, installs, the last known servers, and one listing file per server. Every write goes to a
 * temp file first and is renamed into place.
 */
class HostState(private val dir: Path) : UidAllocator {
    private val file = dir.resolve("host.json")
    private val listings = dir.resolve("listings")
    private var data: HostStateData

    init {
        Files.createDirectories(listings)
        data = if (file.exists()) ClarityJson.decodeFromString(HostStateData.serializer(), file.readText()) else HostStateData()
    }

    val current: HostStateData @Synchronized get() = data

    @Synchronized
    fun update(change: (HostStateData) -> HostStateData) {
        val next = change(data)
        if (next != data) {
            write(file, ClarityJson.encodeToString(HostStateData.serializer(), next))
            data = next
        }
    }

    @Synchronized
    override fun uidFor(id: String): Int {
        data.uids[id]?.let { return it }
        val uid = data.nextUid
        check(uid <= HostStateData.LAST_UID) { "no server uids left" }
        update { it.copy(uids = it.uids + (id to uid), nextUid = uid + 1) }
        return uid
    }

    fun listing(id: String): Listing? {
        val f = listings.resolve("$id.json")
        return if (f.exists()) runCatching { ClarityJson.decodeFromString(Listing.serializer(), f.readText()) }.getOrNull() else null
    }

    fun saveListing(id: String, listing: Listing) = write(listings.resolve("$id.json"), ClarityJson.encodeToString(Listing.serializer(), listing))

    fun dropListing(id: String) { Files.deleteIfExists(listings.resolve("$id.json")) }

    private fun write(target: Path, text: String) {
        val tmp = Files.createTempFile(target.parent, ".tmp-", ".json")
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(tmp, text)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
