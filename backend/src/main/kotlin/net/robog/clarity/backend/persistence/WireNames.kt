package net.robog.clarity.backend.persistence

import kotlinx.serialization.serializer
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.noun.Noun

/** The `@SerialName` of an enum entry: the stable spelling used in SQL text columns and JSON. */
inline fun <reified E : Enum<E>> E.wire(): String = serializer<E>().descriptor.getElementName(ordinal)

/** Inverse of [wire]; null when [name] is not a known spelling. */
inline fun <reified E : Enum<E>> parseWire(name: String): E? {
    val descriptor = serializer<E>().descriptor
    val index = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == name }
    return index?.let { enumValues<E>()[it] }
}

val Noun.wireName: String get() = wire()
val GrantState.wireName: String get() = wire()

fun nounOrNull(wire: String): Noun? = parseWire<Noun>(wire)
fun grantStateOrNull(wire: String): GrantState? = parseWire<GrantState>(wire)
