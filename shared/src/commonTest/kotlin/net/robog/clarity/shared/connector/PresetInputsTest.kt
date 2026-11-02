package net.robog.clarity.shared.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PresetInputsTest {
    private val form = PresetForm(
        inputs = listOf(
            PresetInput("school", "Canvas address", PresetInputType.URL, required = true, path = "/api/v1"),
            PresetInput(
                "timezone", "Time zone", PresetInputType.CHOICE, required = true, default = "America/New_York",
                choices = listOf(PresetChoice("America/New_York", "Eastern"), PresetChoice("America/Chicago", "Central")),
                other = true, pattern = "[A-Za-z_]+(/[A-Za-z0-9_+-]+)*",
            ),
            PresetInput("token", "Access token", PresetInputType.SECRET, required = true, property = "secret"),
        ),
        lists = mapOf("env" to listOf("CANVAS_API_URL={school}", "TIMEZONE={timezone}"), "allowHosts" to listOf("{school.host}")),
    )

    private fun ok(raw: String, path: String = "/api/v1") = (PresetInputs.normalizeUrl(raw, path) as PresetInputs.Result.Ok).value
    private fun bad(raw: String) = (PresetInputs.normalizeUrl(raw, "/api/v1") as PresetInputs.Result.Invalid).error

    @Test fun addressesAreNormalizedToHttpsAndThePath() {
        val want = PresetUrl("https://canvas.ithaca.edu/api/v1", "canvas.ithaca.edu")
        for (raw in listOf(
            "canvas.ithaca.edu", "https://canvas.ithaca.edu", "https://canvas.ithaca.edu/", "https://canvas.ithaca.edu/api/v1",
            "  HTTPS://Canvas.Ithaca.EDU/api/v1/ ", "canvas.ithaca.edu/api/v1", "https://canvas.ithaca.edu:443/courses/12?x=1",
            "canvas.ithaca.edu.",
        )) {
            assertEquals(want, ok(raw), raw)
        }
        assertEquals(PresetUrl("https://api.example.com", "api.example.com"), ok("api.example.com/x", path = ""))
    }

    @Test fun httpOtherPortsAndGarbageAreRefused() {
        assertEquals(PresetInputError.NotHttps, bad("http://canvas.ithaca.edu"))
        assertEquals(PresetInputError.NotHttps, bad("ftp://canvas.ithaca.edu"))
        assertEquals(PresetInputError.WrongPort, bad("https://canvas.ithaca.edu:8443"))
        assertEquals(PresetInputError.Required, bad("   "))
        for (raw in listOf("canvas", "not a url", "https://", "://x.edu", "https://user:pw@canvas.ithaca.edu", "10.0.0.1", "https://[::1]/", "a..b.edu", "-x.edu", "x.edu:abc")) {
            assertEquals(PresetInputError.NotAnAddress, bad(raw), raw)
        }
    }

    @Test fun templatesNeedEveryValue() {
        assertEquals("A=1/x", PresetInputs.fill("A={a}/x", mapOf("a" to "1")))
        assertNull(PresetInputs.fill("A={a}{b}", mapOf("a" to "1")))
        assertEquals("plain", PresetInputs.fill("plain", emptyMap()))
    }

    @Test fun answersFillEnvAndHosts() {
        val answers = mapOf("school" to "canvas.ithaca.edu", "timezone" to "America/Chicago")
        val vars = PresetInputs.variables(form, answers)
        assertEquals(mapOf("school" to "https://canvas.ithaca.edu/api/v1", "school.host" to "canvas.ithaca.edu", "timezone" to "America/Chicago"), vars)
        val out = PresetInputs.apply(form, emptyMap(), answers, PresetTargets(lists = mapOf("env" to listOf("CANVAS_ROLE=student"))))
        assertEquals(listOf("CANVAS_ROLE=student", "CANVAS_API_URL=https://canvas.ithaca.edu/api/v1", "TIMEZONE=America/Chicago"), out.lists["env"])
        assertEquals(listOf("canvas.ithaca.edu"), out.lists["allowHosts"])
    }

    @Test fun changingTheAddressMovesTheHostToo() {
        val a = mapOf("school" to "canvas.ithaca.edu", "timezone" to "America/New_York")
        val first = PresetInputs.apply(form, emptyMap(), a, PresetTargets())
        val b = a + ("school" to "https://canvas.cornell.edu/api/v1")
        val second = PresetInputs.apply(form, a, b, first)
        assertEquals(listOf("CANVAS_API_URL=https://canvas.cornell.edu/api/v1", "TIMEZONE=America/New_York"), second.lists["env"])
        assertEquals(listOf("canvas.cornell.edu"), second.lists["allowHosts"])
        // An address that is not valid yet produces nothing, so no stale host is left behind.
        val half = PresetInputs.apply(form, b, b + ("school" to "http://canvas"), second)
        assertEquals(emptyList(), half.lists["allowHosts"])
    }

    @Test fun valuesChangedByHandAreLeftAlone() {
        val a = mapOf("school" to "canvas.ithaca.edu", "timezone" to "America/New_York")
        val first = PresetInputs.apply(form, emptyMap(), a, PresetTargets())
        // In the full form the person points the URL somewhere else and adds a host.
        val edited = PresetTargets(lists = mapOf(
            "env" to listOf("CANVAS_API_URL=https://lms.example.edu/api/v1", "TIMEZONE=America/New_York"),
            "allowHosts" to listOf("canvas.ithaca.edu", "files.example.edu"),
        ))
        assertEquals(setOf("env"), PresetInputs.diverged(form, a, edited))
        val b = a + ("school" to "canvas.cornell.edu")
        val out = PresetInputs.apply(form, a, b, edited)
        // Their URL stays (and no second CANVAS_API_URL is added); the host the short form set follows the address.
        assertEquals(listOf("CANVAS_API_URL=https://lms.example.edu/api/v1", "TIMEZONE=America/New_York"), out.lists["env"])
        assertEquals(listOf("canvas.cornell.edu", "files.example.edu"), out.lists["allowHosts"])
        assertEquals(first.lists["env"]!!.size, out.lists["env"]!!.size)
    }

    @Test fun scalarsFollowOnlyWhileUnchanged() {
        val f = PresetForm(listOf(PresetInput("site", "Site", PresetInputType.URL)), scalars = mapOf("url" to "{site}/mcp"))
        val one = PresetInputs.apply(f, emptyMap(), mapOf("site" to "a.example.com"), PresetTargets())
        assertEquals("https://a.example.com/mcp", one.texts["url"])
        val two = PresetInputs.apply(f, mapOf("site" to "a.example.com"), mapOf("site" to "b.example.com"), one)
        assertEquals("https://b.example.com/mcp", two.texts["url"])
        val byHand = two.copy(texts = mapOf("url" to "https://c.example.com/mcp"))
        assertEquals("https://c.example.com/mcp", PresetInputs.apply(f, mapOf("site" to "b.example.com"), mapOf("site" to "d.example.com"), byHand).texts["url"])
    }

    @Test fun savedValuesGiveTheAnswersBack() {
        val values = PresetTargets(lists = mapOf(
            "env" to listOf("CANVAS_ROLE=student", "CANVAS_API_URL=https://canvas.ithaca.edu/api/v1", "TIMEZONE=Pacific/Honolulu"),
            "allowHosts" to listOf("canvas.ithaca.edu"),
        ))
        val answers = PresetInputs.recover(form, values)
        assertEquals(mapOf("school" to "https://canvas.ithaca.edu/api/v1", "timezone" to "Pacific/Honolulu"), answers)
        assertEquals(emptySet(), PresetInputs.diverged(form, answers, values))
        // Only the host left: the address is rebuilt from it.
        val f = form.copy(lists = mapOf("allowHosts" to listOf("{school.host}")))
        assertEquals("canvas.ithaca.edu", PresetInputs.recover(f, values)["school"])
        assertEquals(mapOf("school" to "", "timezone" to ""), PresetInputs.recover(form, PresetTargets()))
    }

    @Test fun answersAreChecked() {
        val has = { p: String -> p == "secret" }
        assertEquals(
            mapOf("school" to PresetInputError.NotHttps),
            PresetInputs.errors(form, mapOf("school" to "http://canvas.ithaca.edu", "timezone" to "America/New_York"), has),
        )
        assertEquals(
            mapOf("school" to PresetInputError.Required, "timezone" to PresetInputError.NoMatch, "token" to PresetInputError.Required),
            PresetInputs.errors(form, mapOf("timezone" to "not a zone!"), { false }),
        )
        assertEquals(emptyMap(), PresetInputs.errors(form, mapOf("school" to "canvas.ithaca.edu", "timezone" to "Europe/Berlin"), has))
        val closed = form.inputs[1].copy(other = false)
        assertEquals(PresetInputError.NotAChoice, PresetInputs.error(closed, "Europe/Berlin"))
    }

    @Test fun theWireFormRoundTrips() {
        val preset = JsonObject(mapOf("title" to JsonPrimitive("Canvas")) + form.toJson())
        assertEquals(form, PresetForm.parse(preset))
        assertNull(PresetForm.parse(JsonObject(mapOf("title" to JsonPrimitive("Plain")))))
        // A secret that is not bound to its own property is dropped, never templated.
        val loose = JsonObject(form.copy(inputs = listOf(form.inputs[2].copy(property = null))).toJson())
        assertNull(PresetForm.parse(loose))
    }
}
