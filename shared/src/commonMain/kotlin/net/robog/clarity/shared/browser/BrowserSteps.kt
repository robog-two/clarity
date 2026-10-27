package net.robog.clarity.shared.browser

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One step a helper took in the browser, as the audit trail keeps it: where it was, what it meant
 * to do, what Jev saw and answered, the gate's outcome and what changed. The screenshots are
 * fetched separately; [beforeShot] / [afterShot] say whether one is stored, [beforeWithheld] /
 * [afterWithheld] that one was not kept because a secret may have been on screen.
 */
@Serializable
data class BrowserStepView(
    val id: Long,
    @SerialName("run_id") val runId: String,
    val seq: Int,
    val at: Instant,
    val tool: String,
    @SerialName("tab_url") val tabUrl: String? = null,
    @SerialName("tab_title") val tabTitle: String? = null,
    val intent: String? = null,
    val expect: String? = null,
    @SerialName("before_shot") val beforeShot: Boolean = false,
    @SerialName("before_withheld") val beforeWithheld: Boolean = false,
    val box: JsonObject? = null,
    @SerialName("hit_role") val hitRole: String? = null,
    @SerialName("hit_name") val hitName: String? = null,
    val jev: JsonObject? = null,
    val gate: String,
    val action: String? = null,
    @SerialName("after_shot") val afterShot: Boolean = false,
    @SerialName("after_withheld") val afterWithheld: Boolean = false,
    @SerialName("change_report") val changeReport: String? = null,
    @SerialName("cost_micros") val costMicros: Long? = null,
    /** When the screenshots were dropped by the retention rule; the row itself stays. */
    @SerialName("frames_dropped_at") val framesDroppedAt: Instant? = null,
)
