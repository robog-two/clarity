package net.robog.clarity.backend.ingest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hot, bounded, in-process fan-out. Publishing never suspends or blocks ingest: a subscriber that
 * falls more than [CAPACITY] events behind loses its oldest events (`DROP_OLDEST`), which is the
 * right trade for best-effort triggers; durable consumers re-read via `NounReader.changedSince`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class InMemoryIngestEventBus : IngestEventBus {
    private val flow = MutableSharedFlow<Ingested>(extraBufferCapacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val events: SharedFlow<Ingested> = flow.asSharedFlow()

    override suspend fun publish(event: Ingested) {
        flow.emit(event)
    }

    private companion object { const val CAPACITY = 1024 }
}
