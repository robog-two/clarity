package net.robog.clarity.shared.noun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An address something can be reached at: an email address, a phone number, a chat handle. */
@Serializable
data class Handle(
    val kind: Kind,
    val value: String,
    val displayName: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("email") EMAIL,
        @SerialName("phone") PHONE,
        @SerialName("other") OTHER,
    }

    companion object {
        fun email(value: String, displayName: String? = null) = Handle(Kind.EMAIL, value, displayName)
        fun phone(value: String, displayName: String? = null) = Handle(Kind.PHONE, value, displayName)
    }
}
