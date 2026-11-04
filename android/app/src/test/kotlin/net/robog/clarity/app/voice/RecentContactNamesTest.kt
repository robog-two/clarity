package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentContactNamesTest {
    private val book = mapOf(
        "+15555550123" to "Sam Example",
        "+15555550124" to "Alex Sample",
        "+15555550125" to "Jo Test",
    )
    private val lookup: (String) -> String? = { book[it] }

    @Test fun `newest first and distinct`() {
        val out = recentContactNames(listOf("+15555550124", "+15555550123", "+15555550124", "+15555550125"), lookup)
        assertEquals(listOf("Alex Sample", "Sam Example", "Jo Test"), out)
    }

    @Test fun `codes and unknown numbers are skipped and do not count`() {
        val addresses = listOf("12345", "GOVT-ALERT", "+15555559999", "+15555550123", "898989", "+15555550124")
        assertEquals(listOf("Sam Example", "Alex Sample"), recentContactNames(addresses, lookup))
        assertEquals(listOf("Sam Example"), recentContactNames(addresses, lookup, max = 1))
    }

    @Test fun `stops at the contact limit not the text count`() {
        val numbers = (0 until 40).map { "+1555555%04d".format(it) }
        val names = numbers.associateWith { "Person $it" }
        val noise = List(100) { "+15550001111" }
        assertEquals(20, recentContactNames(noise + numbers, { names[it] }).size)
    }

    @Test fun `same number in another format counts once`() {
        val out = recentContactNames(listOf("+15555550123", "(555) 555-0123"), { "Sam Example" })
        assertEquals(listOf("Sam Example"), out)
    }

    @Test fun `hint words put contacts first with full names and parts`() {
        val out = mergeHotwords(listOf("Sam Example"), listOf("Calc", "Sam", "Biology"))
        assertEquals(listOf("Sam Example", "Sam", "Example", "Calc", "Biology"), out)
    }

    @Test fun `hint words keep the cap`() {
        val names = (0 until 40).map { "Fname${"abcdefghijklmnopqrstuvwxyz"[it % 26]}${"abcdefghij"[it / 26]} Lname" }
        assertEquals(MAX_HOTWORDS, mergeHotwords(names, emptyList()).size)
    }

    @Test fun `hint words drop what cannot be encoded`() {
        val out = mergeHotwords(listOf("Zoë Example"), emptyList())
        assertTrue(out.none { it.contains('ë') })
        assertTrue("Example" in out)
    }

    @Test fun `without permission nothing is read`() {
        var read = false
        val names = RecentContactNames(granted = { false }, texts = { read = true; emptyList() }, contacts = { null })
        assertEquals(emptyList(), names.names())
        assertEquals(false, read)
    }

    @Test fun `scan stops at the newest texts and works through fake providers`() {
        var asked = 0
        val texts = RecentTexts { limit -> asked = limit; List(900) { "+15555550123" } }
        val names = RecentContactNames({ true }, texts, { book[it] }, scan = 500)
        assertEquals(listOf("Sam Example"), names.names())
        assertEquals(500, asked)
    }

    @Test fun `a failing provider gives an empty list`() {
        val names = RecentContactNames({ true }, { error("boom") }, { null })
        assertEquals(emptyList(), names.names())
    }

    @Test fun `cache holds for ten minutes`() {
        var t = 0L
        var loads = 0
        val cache = TimedCache<List<String>>(600_000, { t })
        cache.get { loads++; listOf("a") }
        t = 599_000
        cache.get { loads++; listOf("b") }
        assertEquals(1, loads)
        t = 601_000
        assertEquals(listOf("c"), cache.get { loads++; listOf("c") })
        assertEquals(2, loads)
    }
}
