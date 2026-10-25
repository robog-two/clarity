package net.robog.clarity.app

/** Build-time behaviour switches, injected so logic stays testable. */
data class AppConfig(
    /** Debug builds may talk to http:// instances; release builds are HTTPS only. */
    val allowCleartext: Boolean,
)
