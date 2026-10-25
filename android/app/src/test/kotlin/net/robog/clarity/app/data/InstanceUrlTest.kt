package net.robog.clarity.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class InstanceUrlTest {
    private fun v(raw: String, cleartext: Boolean = false) = InstanceUrl.validate(raw, cleartext)

    @Test fun acceptsHttpsAndNormalizes() {
        assertEquals(UrlValidation.Valid("https://clarity.example.com"), v("  HTTPS://Clarity.Example.com/ "))
        assertEquals(UrlValidation.Valid("https://h.example:8443/base"), v("https://h.example:8443/base/"))
        assertEquals(UrlValidation.Valid("https://h.example"), v("https://h.example?x=1#frag"))
    }

    @Test fun httpOnlyWhenCleartextAllowed() {
        assertEquals(UrlValidation.Invalid(UrlError.InsecureScheme), v("http://10.0.2.2:8080"))
        assertEquals(UrlValidation.Valid("http://10.0.2.2:8080"), v("http://10.0.2.2:8080", cleartext = true))
    }

    @Test fun rejectsBadInput() {
        assertEquals(UrlValidation.Invalid(UrlError.Empty), v("   "))
        assertEquals(UrlValidation.Invalid(UrlError.UnsupportedScheme), v("clarity.example.com"))
        assertEquals(UrlValidation.Invalid(UrlError.UnsupportedScheme), v("ftp://x.example"))
        assertEquals(UrlValidation.Invalid(UrlError.Malformed), v("https://"))
        assertEquals(UrlValidation.Invalid(UrlError.Malformed), v("https://user:pw@x.example"))
        assertEquals(UrlValidation.Invalid(UrlError.Malformed), v("https://exa mple.com"))
    }
}
