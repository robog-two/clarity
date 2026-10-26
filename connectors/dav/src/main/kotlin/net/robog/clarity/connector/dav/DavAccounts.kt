package net.robog.clarity.connector.dav

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.robog.clarity.connector.sdk.AccountTest
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorTestResult
import net.robog.clarity.connector.sdk.SealingException
import net.robog.clarity.connector.sdk.StateStore
import net.robog.clarity.connector.sdk.combineAccountTests
import net.robog.clarity.connector.sdk.runIsolated
import net.robog.clarity.connector.sdk.scoped
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.ingest.IngestRecord

private val log = KotlinLogging.logger {}

/** What syncing one account did: what it found, or why it failed. */
data class AccountSync(val id: String, val label: String, val result: Result<DavSync.Counts>)

/**
 * Syncs every account in [entries], one after the other, each with its own server connection and
 * its own state under its id. An account that cannot be read or whose sync fails is reported and
 * does not stop the others.
 */
suspend fun syncAccounts(
    entries: List<DavAccountEntry>,
    state: StateStore,
    push: suspend (List<IngestRecord>) -> Unit,
    connect: (DavConfig) -> DavServer = { DavServer(it) },
): List<AccountSync> = entries.map { entry ->
    val result = runIsolated {
        val config = entry.result.getOrThrow()
        connect(config).use { server -> DavSync(server, config, state.scoped(config.id)).syncAll(push) }
    }
    result.onFailure { log.warn(it) { "sync of calendar account '${entry.label}' failed; the other accounts continue" } }
    AccountSync(entry.id, entry.label, result)
}

/** Signs in to one account, discovers what it offers and counts it. */
private suspend fun testAccount(config: DavConfig, connect: (DavConfig) -> DavServer): AccountTest = connect(config).use { server ->
    val d = server.discover()
    val folders = config.webdavFolders.size
    if (d.calendars.isEmpty() && d.addressBooks.isEmpty() && folders == 0) {
        AccountTest(false, "no calendars or address books found (check the server URL and password)")
    } else {
        AccountTest(
            true,
            "${d.calendars.size} calendars, ${d.addressBooks.size} address books" + if (folders > 0) ", $folders folders to index" else "",
            mapOf("calendars" to d.calendars.size, "addressBooks" to d.addressBooks.size, "folders" to folders),
        )
    }
}

/**
 * What the app's "test connection" button runs, for every account:
 * `"iCloud: 3 calendars, 1 address books · Work: could not connect: ..."`.
 */
suspend fun testDavAccounts(runtime: ConnectorRuntime, config: ConnectorConfig, connect: (DavConfig) -> DavServer = { DavServer(it) }): ConnectorTestResult = coroutineScope {
    parseDavAccounts(config, runtime).map { entry ->
        async {
            val test = try {
                testAccount(entry.result.getOrThrow(), connect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SealingException) {
                AccountTest(false, "the saved password could not be read; enter it again")
            } catch (e: ConfigIncomplete) {
                AccountTest(false, e.message.orEmpty().removePrefix("${entry.label}: "))
            } catch (e: Exception) {
                AccountTest(false, "could not connect: ${e.message ?: e::class.simpleName}")
            }
            entry.label to test
        }
    }.awaitAll().let(::combineAccountTests)
}
