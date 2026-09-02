package pro.ivol.voicebridge.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RussianEnglishSpokenVndParserTest {
    @Test
    fun `parses common Russian spoken million and thousand prices`() {
        assertEquals(15_000_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("пятнадцать миллионов"))
        assertEquals(100_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("сто тысяч"))
        assertEquals(
            15_500_000L,
            RussianEnglishSpokenVndParser.parseMostLikelyVnd(
                "цена пятнадцать миллионов пятьсот тысяч донгов",
            ),
        )
        assertEquals(21_000_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("двадцать один миллион"))
    }

    @Test
    fun `parses common English spoken million and thousand prices`() {
        assertEquals(15_000_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("fifteen million"))
        assertEquals(100_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("one hundred thousand"))
        assertEquals(
            15_500_000L,
            RussianEnglishSpokenVndParser.parseMostLikelyVnd(
                "the price is fifteen million five hundred thousand dong",
            ),
        )
        assertEquals(125_000L, RussianEnglishSpokenVndParser.parseMostLikelyVnd("one hundred twenty five thousand"))
    }

    @Test
    fun `ignores ordinary word numbers without thousand or million scale`() {
        assertNull(RussianEnglishSpokenVndParser.parseMostLikelyVnd("пятьсот рублей"))
        assertNull(RussianEnglishSpokenVndParser.parseMostLikelyVnd("nine cats"))
        assertNull(RussianEnglishSpokenVndParser.parseMostLikelyVnd("2 million"))
    }
}
