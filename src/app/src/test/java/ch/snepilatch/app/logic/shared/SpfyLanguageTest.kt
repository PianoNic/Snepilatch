package ch.snepilatch.app.logic.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #733: the session is created with the language spfy should answer in. */
class SpfyLanguageTest {

    @Test
    fun aPickedLanguage_isSentAsIs() {
        assertEquals("de", spfyLanguage("de", "en"))
        assertEquals("ru", spfyLanguage("ru", "en"))
    }

    @Test
    fun system_followsTheDeviceLanguage() {
        assertEquals("fr", spfyLanguage("system", "fr"))
    }

    @Test
    fun swissGerman_getsTheGermanSpfyServes() {
        assertEquals("de", spfyLanguage("gsw", "en"))
    }
}
