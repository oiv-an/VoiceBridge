package pro.ivol.voicebridge.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalScreenTranslationClientLanguageTest {
    @Test
    fun `unsupported element language falls back to dominant page language`() {
        val resolved = resolveIdentifiedLanguage(
            detectedTag = "unsupported-tag",
            fallbackLanguageCode = "vi",
            failOnUnsupportedLanguage = false,
            normalizeLanguageCode = { throw IllegalArgumentException("unsupported") },
        )

        assertEquals("vi", resolved)
    }

    @Test
    fun `unsupported dominant page language remains an error`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveIdentifiedLanguage(
                detectedTag = "unsupported-tag",
                fallbackLanguageCode = "vi",
                failOnUnsupportedLanguage = true,
                normalizeLanguageCode = { throw IllegalArgumentException("unsupported") },
            )
        }
    }

    @Test
    fun `supported element keeps its normalized detected language`() {
        val resolved = resolveIdentifiedLanguage(
            detectedTag = "EN-us",
            fallbackLanguageCode = "vi",
            failOnUnsupportedLanguage = false,
            normalizeLanguageCode = { "en" },
        )

        assertEquals("en", resolved)
    }
}
