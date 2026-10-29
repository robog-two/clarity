package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.SpendLimits
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select

private val log = KotlinLogging.logger {}

/** A hard spend stop: the period it holds for ([periodKey], one notice each) and what to say. */
data class SpendStop(val periodKey: String, val text: String)

/** Thrown instead of making a model call while a [SpendStop] holds. */
class SpendLimitReached(val stop: SpendStop) : RuntimeException(stop.text)

/** Says before each model call whether a hard spend limit stops it. */
fun interface SpendGuard {
    suspend fun check(role: AgentRole?): SpendStop?

    companion object {
        val OFF = SpendGuard { null }
    }
}

/** Pure: a dollar amount as the person reads it. */
fun usd(micros: Long): String = "$" + "%.2f".format(micros / 1_000_000.0)

/**
 * Pure: the stop that what was spent reaches under [limits], or null: [spentWeek] (every call in the
 * last 7 days) first, then [spentToday] by [role] in the last 24 hours. [now] names the period.
 */
fun spendStop(limits: SpendLimits, role: AgentRole?, spentWeek: Long, spentToday: Long, now: Instant): SpendStop? {
    val date = now.toLocalDateTime(TimeZone.UTC).date
    val weekly = dollarsToMicros(limits.weeklyUsd)
    if (spentWeek >= weekly) {
        return SpendStop(
            "week:$date",
            "Spend limit reached: model calls cost ${usd(spentWeek)} in the last 7 days (the limit is ${usd(weekly)}). " +
                "No model is called until older calls age out.",
        )
    }
    val daily = role?.let { limits.dailyUsd[it] }?.let(::dollarsToMicros) ?: return null
    if (spentToday < daily) return null
    return SpendStop(
        "day:${role.wireName}:$date",
        "Spend limit reached: the ${role.wireName} spent ${usd(spentToday)} in the last 24 hours (the limit is ${usd(daily)}). " +
            "Its model calls stop until older calls age out.",
    )
}

/** Mirrors db/migration/V35__runaway_guards.sql. */
object SpendNotices : Table("spend_notice") {
    val periodKey = text("period_key")
    val postedAt = timestamp("posted_at")
    override val primaryKey = PrimaryKey(periodKey)
}

/**
 * The [SpendGuard] over `model_call`: the limits of [AgentConfig.spend]. The first stop of each
 * period puts one short note in the person's feed (a hard stop is something they want to know;
 * nothing else is posted for them) and logs a warning.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DbSpendGuard(
    private val database: Database,
    private val clock: Clock,
    private val config: AgentConfig,
    private val feed: FeedService,
) : SpendGuard {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    override suspend fun check(role: AgentRole?): SpendStop? {
        val now = clock.now()
        val (week, today) = database.tx {
            val total = ModelCalls.costMicros.sum()
            val week = ModelCalls.select(total).where { ModelCalls.at greaterEq (now - 7.days) }.singleOrNull()?.get(total) ?: 0L
            val today = role?.let { r ->
                ModelCalls.select(total).where { (ModelCalls.at greaterEq (now - 24.hours)) and (ModelCalls.role eq r.wireName) }
                    .singleOrNull()?.get(total)
            } ?: 0L
            week to today
        }
        val stop = spendStop(config.spend, role, week, today, now) ?: return null
        if (warned.add(stop.periodKey)) log.warn { stop.text }
        notifyOnce(stop, now)
        return stop
    }

    private suspend fun notifyOnce(stop: SpendStop, now: Instant) {
        val first = database.tx {
            SpendNotices.insertIgnore {
                it[periodKey] = stop.periodKey
                it[postedAt] = now
            }.insertedCount > 0
        }
        if (first) feed.append(FeedDraft(author = FeedItem.Author.AGENT, cards = listOf(Card.Note(stop.text))))
    }
}
