package pro.ivol.voicebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationFontSizeTest {
    @Test
    fun `translation font becomes smaller as text grows`() {
        assertEquals(32, translationFontSizeSp("a".repeat(60)))
        assertEquals(29, translationFontSizeSp("a".repeat(61)))
        assertEquals(26, translationFontSizeSp("a".repeat(121)))
        assertEquals(23, translationFontSizeSp("a".repeat(191)))
        assertEquals(20, translationFontSizeSp("a".repeat(271)))
    }

    @Test
    fun `explicit lines contribute to translation density`() {
        val shortSingleLine = "Short translation"
        val sameWordsOnFiveLines = "Short\ntranslation\non\nfive\nlines"

        assertEquals(32, translationFontSizeSp(shortSingleLine))
        assertEquals(26, translationFontSizeSp(sameWordsOnFiveLines))
    }
}
