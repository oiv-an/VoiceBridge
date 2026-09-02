package pro.ivol.voicebridge.translation

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VietnamesePronunciationTest {
    @Test
    fun `common short travel phrases use Russian-friendly Southern pronunciation`() {
        assertEquals("Син→ чау↘", VietnamesePronunciation.shortRussianHint("Xin chào"))
        assertEquals("Кам↘↗ онг→", VietnamesePronunciation.shortRussianHint("Cảm ơn"))
        assertEquals(
            "Бау→ ниэу→ тиэнг↘?",
            VietnamesePronunciation.shortRussianHint("Bao nhiêu tiền?"),
        )
        assertEquals("Той→ муонг↗", VietnamesePronunciation.shortRussianHint("Tôi muốn"))
    }

    @Test
    fun `tone arrows distinguish all written Vietnamese tones`() {
        assertEquals(
            "ма→ ма↗ ма↘ ма↘↗ ма↘↗ ма↓",
            VietnamesePronunciation.shortRussianHint("ma má mà mả mã mạ", maxWords = 6),
        )
    }

    @Test
    fun `visually identical NFC and NFD text produce the same hint`() {
        val phrase = "Cảm ơn"
        val decomposed = Normalizer.normalize(phrase, Normalizer.Form.NFD)

        assertEquals(
            VietnamesePronunciation.shortRussianHint(phrase),
            VietnamesePronunciation.shortRussianHint(decomposed),
        )
    }

    @Test
    fun `six word phrase is hidden`() {
        assertNull(VietnamesePronunciation.shortRussianHint("xin chào cảm ơn bạn nhiều"))
    }

    @Test
    fun `multiple sentences are hidden`() {
        assertNull(VietnamesePronunciation.shortRussianHint("Xin chào. Cảm ơn."))
    }
}
