package net.robog.clarity.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestReport
import net.robog.clarity.shared.connector.ConnectorTestResult

class ConnectorTestWaitTest {
    private val id = ConnectorId("mail")
    private val at = Instant.parse("2026-10-26T04:22:22Z")

    private fun listed(report: ConnectorTestReport?) =
        ApiResult.Success(listOf(ConnectorSummary(id, manifest = null, grants = emptyMap(), lastSeenAt = null, lastTest = report)))

    @Test fun aFinishedResultIsReturnedAsIs() = runTest {
        val done = ConnectorTestResult(ok = true, summary = "iCloud: 5 calendars", requestId = "r1")
        assertEquals(done, awaitTestResult(id, done, list = { error("not listed") }))
    }

    @Test fun aPendingTestIsReadFromLastTestOnceTheConnectorReports() = runTest {
        val pending = ConnectorTestResult(ok = false, error = "still testing", pending = true, requestId = "r2")
        var calls = 0
        val result = awaitTestResult(id, pending, every = 1.milliseconds, list = {
            calls++
            when (calls) {
                1 -> listed(ConnectorTestReport("r1", false, "an older test", emptyMap(), at)) // not this request
                else -> listed(ConnectorTestReport("r2", true, "iCloud: 18,151 messages", emptyMap(), at))
            }
        })
        assertEquals(ConnectorTestResult(ok = true, summary = "iCloud: 18,151 messages", requestId = "r2"), result)
    }

    @Test fun aFailedReportKeepsItsMessage() = runTest {
        val pending = ConnectorTestResult(ok = false, pending = true, requestId = "r3")
        val result = awaitTestResult(id, pending, every = 1.milliseconds, list = {
            listed(ConnectorTestReport("r3", false, "Gmail: password rejected", emptyMap(), at))
        })
        assertEquals("Gmail: password rejected", result.error)
        assertEquals(false, result.ok)
    }

    @Test fun givingUpSaysItIsStillTestingNotThatItFailed() = runTest {
        val pending = ConnectorTestResult(ok = false, pending = true, requestId = "r4")
        val result = awaitTestResult(id, pending, every = 1.milliseconds, tries = 3, list = { listed(null) })
        assertEquals("still testing: check again in a minute", result.error)
    }
}
