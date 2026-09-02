package pro.ivol.voicebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.ivol.voicebridge.domain.AppLanguage

class VietnamesePronunciationSelectorTest {
    @Test
    fun `current Vietnamese translation is selected`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.VIETNAMESE,
            transcript = "  Привет  ",
            translation = "Xin chào",
            translatedSourceText = "Привет",
        )

        assertEquals("Xin chào", state.vietnameseTextForPronunciation())
    }

    @Test
    fun `stale Vietnamese translation is rejected`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.VIETNAMESE,
            transcript = "Новый текст",
            translation = "Xin chào",
            translatedSourceText = "Старый текст",
        )

        assertNull(state.vietnameseTextForPronunciation())
    }

    @Test
    fun `Vietnamese recognized text is selected in reverse direction`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.VIETNAMESE,
            targetLanguage = AppLanguage.RUSSIAN,
            transcript = "  Cảm ơn  ",
        )

        assertEquals("Cảm ơn", state.vietnameseTextForPronunciation())
    }

    @Test
    fun `pair without Vietnamese has no pronunciation source`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.ENGLISH,
            transcript = "Привет",
            translation = "Hello",
            translatedSourceText = "Привет",
        )

        assertNull(state.vietnameseTextForPronunciation())
    }
}
