package net.robog.clarity.backend.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import net.robog.clarity.backend.config.DatabaseConfig
import org.flywaydb.core.Flyway

/** Builds the connection pool and applies Flyway migrations. DDL lives only in `db/migration`. */
object DatabaseFactory {
    fun dataSource(config: DatabaseConfig): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            poolName = "clarity"
            jdbcUrl = config.url
            username = config.user
            password = config.password?.value
            maximumPoolSize = config.maxPoolSize
            initializationFailTimeout = 60_000 // wait for Postgres to come up in compose
            isAutoCommit = false // Exposed manages transaction boundaries
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        },
    )

    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
