package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle

private val log = KotlinLogging.logger {}

/** What syncing one account did: records pushed, or why it failed. */
data class AccountSync(val id: String, val label: String, val result: Result<Int>)

/**
 * Syncs every account in [entries], one after the other, each with its own state under its id.
 * An account that cannot be read or whose sync fails is reported and does not stop the others.
 */
suspend fun syncAccounts(
    entries: List<MailAccountEntry>,
    state: StateStore,
    push: suspend (List<IngestRecord>) -> Unit,
    syncFor: (MailConfig, StateStore) -> MailSync = { config, store -> MailSync(MailAccess(config), store.scoped(config.id)) },
): List<AccountSync> = entries.map { entry ->
    val result = runIsolated { syncFor(entry.result.getOrThrow(), state).syncAll(push) }
    result.onFailure { log.warn(it) { "sync of mail account '${entry.label}' failed; the other accounts continue" } }
    AccountSync(entry.id, entry.label, result)
}

/**
 * The account an email is sent or drafted from: the one whose `fromAddress` or `username` is
 * [from], and none when no account has that address (it is never sent from another one). Without
 * [from] (an email held before the sender was chosen): the one that received the message at
 * [replyTo], else the first.
 */
fun selectAccount(accounts: List<MailConfig>, from: Handle?, replyTo: SourceId?): MailConfig? {
    from?.value?.trim()?.takeIf(String::isNotEmpty)?.let { wanted ->
        return accounts.firstOrNull { it.fromAddress.equals(wanted, ignoreCase = true) || it.username.equals(wanted, ignoreCase = true) }
    }
    replyTo?.value?.let { MailRef.parse(it, accounts.map(MailConfig::id)) }?.let { ref ->
        accounts.firstOrNull { it.id == ref.accountId }?.let { return it }
    }
    return accounts.firstOrNull()
}

private fun failureText(e: Throwable): String = when (e) {
    is AuthenticationFailedException -> "password rejected"
    is SealingException -> "the saved password could not be read; enter it again"
    is ConfigIncomplete -> e.message.orEmpty().substringAfter(": ")
    else -> "could not connect: ${e.message ?: e::class.simpleName}"
}

/**
 * What the app's "test connection" button runs: for every account log in to IMAP and SMTP and
 * count the messages of its folders. `"Work: 4,218 messages · Personal: password rejected"`.
 */
suspend fun testMailAccounts(runtime: ConnectorRuntime, config: ConnectorConfig): ConnectorTestResult = coroutineScope {
    parseMailAccounts(config, runtime).map { entry ->
        async(Dispatchers.IO) {
            val test = try {
                val check = MailAccess(entry.result.getOrThrow()).check()
                AccountTest(true, "%,d messages".format(check.messages), mapOf("folders" to check.folders, "messages" to check.messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccountTest(false, failureText(e))
            }
            entry.label to test
        }
    }.awaitAll().let(::combineAccountTests)
}
