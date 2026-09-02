package pro.ivol.voicebridge.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniversalVndAmountParserTest {
    @Test
    fun `accepts every bare integer from one thousand`() {
        assertNull(UniversalVndAmountParser.parseMostLikelyVnd("999"))
        assertEquals(1_000L, UniversalVndAmountParser.parseMostLikelyVnd("1000"))
        assertEquals(15_000_000L, UniversalVndAmountParser.parseMostLikelyVnd("Цена 15000000"))
    }

    @Test
    fun `accepts dot comma and whitespace digit grouping`() {
        listOf(
            "1.500.000",
            "1,500,000",
            "1 500 000",
            "1\u00a0500\u00a0000",
            "1\u202f500\u202f000",
        ).forEach { text ->
            assertEquals(text, 1_500_000L, UniversalVndAmountParser.parseMostLikelyVnd(text))
        }
    }

    @Test
    fun `supports numeric scales in Vietnamese Russian and English`() {
        assertEquals(1_500_000L, UniversalVndAmountParser.parseMostLikelyVnd("1,5 triệu"))
        assertEquals(250_000L, UniversalVndAmountParser.parseMostLikelyVnd("250 nghìn"))
        assertEquals(2_000L, UniversalVndAmountParser.parseMostLikelyVnd("2 ngàn"))

        assertEquals(15_000_000L, UniversalVndAmountParser.parseMostLikelyVnd("15 миллионов"))
        assertEquals(3_000L, UniversalVndAmountParser.parseMostLikelyVnd("3 тысячи"))
        assertEquals(1_500_000L, UniversalVndAmountParser.parseMostLikelyVnd("1,5 миллиона"))

        assertEquals(15_000_000L, UniversalVndAmountParser.parseMostLikelyVnd("15 million"))
        assertEquals(3_000L, UniversalVndAmountParser.parseMostLikelyVnd("3 thousand"))
        assertEquals(2_500L, UniversalVndAmountParser.parseMostLikelyVnd("2.5 thousand"))
        assertEquals(500_000L, UniversalVndAmountParser.parseMostLikelyVnd("500k"))
    }

    @Test
    fun `keeps existing Vietnamese word amount support`() {
        assertEquals(
            1_200_000L,
            UniversalVndAmountParser.parseMostLikelyVnd("một triệu hai trăm nghìn"),
        )
    }

    @Test
    fun `does not reject phone like numbers`() {
        assertEquals(901_234_567L, UniversalVndAmountParser.parseMostLikelyVnd("0901234567"))
        assertEquals(90_123_456L, UniversalVndAmountParser.parseMostLikelyVnd("090 123 456"))
        assertEquals(84_901_234_567L, UniversalVndAmountParser.parseMostLikelyVnd("+84 901 234 567"))
    }

    @Test
    fun `returns unique amounts in appearance order`() {
        assertEquals(
            listOf(1_000L, 2_000_000L, 1_500_000L),
            UniversalVndAmountParser.parseAllVnd("1000, 2 million, 1.500.000, 1000"),
        )
        assertEquals(
            listOf(1_000L, 2_000L),
            UniversalVndAmountParser.parseAllVnd("1000, 2000"),
        )
        assertEquals(emptyList<Long>(), UniversalVndAmountParser.parseAllVnd("999 and 12"))
        assertEquals(
            listOf(2_026L, 15_000_000L),
            UniversalVndAmountParser.parseAllVnd("модель 2026 стоит пятнадцать миллионов"),
        )
    }

    @Test
    fun `most likely prefers marked amount over earlier bare number`() {
        assertEquals(
            2_000_000L,
            UniversalVndAmountParser.parseMostLikelyVnd("Модель 2026, цена 2 миллиона"),
        )
        assertEquals(
            1_000_000L,
            UniversalVndAmountParser.parseMostLikelyVnd("1 million or 2 million"),
        )
    }

    @Test
    fun `rejects malformed and overflowing numeric amounts without throwing`() {
        assertNull(UniversalVndAmountParser.parseMostLikelyVnd("12.34.567"))
        assertNull(UniversalVndAmountParser.parseMostLikelyVnd("1..000"))
        assertNull(UniversalVndAmountParser.parseMostLikelyVnd("9223372036854775808"))
        assertEquals(
            Long.MAX_VALUE,
            UniversalVndAmountParser.parseMostLikelyVnd("9 223 372 036 854 775 807"),
        )
    }

    @Test
    fun `zero integer scaled decimal is not mistaken for a grouped integer`() {
        assertEquals(1_000L, UniversalVndAmountParser.parseMostLikelyVnd("0,001 triệu"))
        assertEquals(1_000L, UniversalVndAmountParser.parseMostLikelyVnd("0.001 million"))
    }

    @Test
    fun `formats integer tokens but preserves decimal scale notation`() {
        assertEquals(
            "Цена 15 000 000, залог 1 500 000, скидка 1,5 triệu.",
            UniversalVndAmountParser.formatRecognizedText(
                "Цена 15000000, залог 1.500.000, скидка 1,5 triệu.",
            ),
        )
        assertEquals("999 и 1 000", UniversalVndAmountParser.formatRecognizedText("999 и 1000"))
        assertEquals("abc1500 1500abc", UniversalVndAmountParser.formatRecognizedText("abc1500 1500abc"))
    }

    @Test
    fun `formatter preserves leading zeroes and is idempotent`() {
        val formatted = UniversalVndAmountParser.formatRecognizedText("0901234567")
        assertEquals("0 901 234 567", formatted)
        assertEquals(formatted, UniversalVndAmountParser.formatRecognizedText(formatted))
    }
}
