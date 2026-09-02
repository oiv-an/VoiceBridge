package pro.ivol.voicebridge.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VietnameseMoneyParserTest {
    @Test
    fun `parses decimal millions`() {
        assertEquals(1_500_000L, VietnameseMoneyParser.parseMostLikelyVnd("1,5 triệu"))
        assertEquals(1_500_000L, VietnameseMoneyParser.parseMostLikelyVnd("1.5 trieu"))
        assertEquals(2_250_000L, VietnameseMoneyParser.parseMostLikelyVnd("2,25 triệu đồng"))
    }

    @Test
    fun `parses grouped digits with Vietnamese currencies`() {
        assertEquals(1_500_000L, VietnameseMoneyParser.parseMostLikelyVnd("1.500.000 ₫"))
        assertEquals(1_500_000L, VietnameseMoneyParser.parseMostLikelyVnd("1,500,000 VND"))
        assertEquals(1_500_000L, VietnameseMoneyParser.parseMostLikelyVnd("1 500 000 đồng"))
        assertEquals(2_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("VND 2.000.000"))
        assertEquals(850_000L, VietnameseMoneyParser.parseMostLikelyVnd("Giá là 850000đ"))
        assertEquals(500L, VietnameseMoneyParser.parseMostLikelyVnd("500 dong"))
    }

    @Test
    fun `parses numeric thousand and million scales`() {
        assertEquals(500_000L, VietnameseMoneyParser.parseMostLikelyVnd("500k"))
        assertEquals(250_000L, VietnameseMoneyParser.parseMostLikelyVnd("250 nghìn"))
        assertEquals(2_000L, VietnameseMoneyParser.parseMostLikelyVnd("2 ngàn"))
        assertEquals(3_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("3 trieu"))
        assertEquals(1_500_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("1.500 triệu"))
    }

    @Test
    fun `parses accented Vietnamese number words`() {
        assertEquals(
            1_200_000L,
            VietnameseMoneyParser.parseMostLikelyVnd("một triệu hai trăm nghìn"),
        )
        assertEquals(
            2_500_000L,
            VietnameseMoneyParser.parseMostLikelyVnd("hai triệu năm trăm nghìn đồng"),
        )
        assertEquals(10_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("mười triệu"))
        assertEquals(900_000L, VietnameseMoneyParser.parseMostLikelyVnd("chín trăm ngàn"))
        assertEquals(200L, VietnameseMoneyParser.parseMostLikelyVnd("hai trăm đồng"))
    }

    @Test
    fun `parses accentless Vietnamese number words`() {
        assertEquals(
            1_200_000L,
            VietnameseMoneyParser.parseMostLikelyVnd("mot trieu hai tram nghin"),
        )
        assertEquals(25_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("hai muoi nam trieu"))
        assertEquals(120_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("mot tram hai muoi trieu"))
        assertEquals(101_000L, VietnameseMoneyParser.parseMostLikelyVnd("mot tram mot nghin"))
    }

    @Test
    fun `bare large numeric amount is opt in`() {
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("1 500 000"))
        assertEquals(
            1_500_000L,
            VietnameseMoneyParser.parseMostLikelyVnd("1 500 000", allowBareLargeNumeric = true),
        )
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("999 999", allowBareLargeNumeric = true))
    }

    @Test
    fun `rejects phone like digit strings even when bare numbers are enabled`() {
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("0901234567", allowBareLargeNumeric = true))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("Số điện thoại 090 123 456", allowBareLargeNumeric = true))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("phone 123 456 789", allowBareLargeNumeric = true))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("+84 901 234 567", allowBareLargeNumeric = true))
    }

    @Test
    fun `rejects non price text and malformed amounts`() {
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd(""))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("Tôi có một con mèo"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("một trăm"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("triệu"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("12.34.567 ₫"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("một nghìn triệu"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("1500 d"))
        assertNull(VietnameseMoneyParser.parseMostLikelyVnd("Hẹn ngày 23.08.2026", allowBareLargeNumeric = true))
    }

    @Test
    fun `prefers explicit currency candidate over scale only candidate`() {
        assertEquals(
            1_800_000L,
            VietnameseMoneyParser.parseMostLikelyVnd("Ngân sách khoảng 2 triệu, giá chính xác 1.800.000 đồng"),
        )
    }

    @Test
    fun `uses first candidate when confidence is equal`() {
        assertEquals(1_000_000L, VietnameseMoneyParser.parseMostLikelyVnd("Giá 1 triệu hoặc 2 triệu"))
    }
}
