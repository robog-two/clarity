package net.robog.clarity.backend.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [block] in one database transaction on [Dispatchers.IO] so JDBC never blocks the Ktor
 * event loop. Commits on normal return, rolls back on any exception.
 */
suspend fun <T> Database.tx(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction(this@tx) { block() } }
