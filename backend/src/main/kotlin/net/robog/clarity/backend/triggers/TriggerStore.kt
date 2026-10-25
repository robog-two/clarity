package net.robog.clarity.backend.triggers

import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.TriggerSpec

data class StoredTrigger(val id: String, val spec: TriggerSpec)

interface TriggerStore {
    suspend fun list(): List<StoredTrigger>
    suspend fun enabledFor(noun: Noun): List<StoredTrigger>
    suspend fun create(spec: TriggerSpec): StoredTrigger
    suspend fun delete(id: String): Boolean
}
