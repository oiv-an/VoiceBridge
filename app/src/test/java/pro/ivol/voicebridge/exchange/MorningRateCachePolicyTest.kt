package pro.ivol.voicebridge.exchange

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MorningRateCachePolicyTest {
    private val vietnamTime = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    @Test
    fun `evening quote stays fresh overnight until six in the morning`() {
        val evening = localEpoch(2026, Calendar.AUGUST, 23, 20, 0, vietnamTime)
        val beforeMorningRefresh = localEpoch(2026, Calendar.AUGUST, 24, 5, 59, vietnamTime)
        val atMorningRefresh = localEpoch(2026, Calendar.AUGUST, 24, 6, 0, vietnamTime)

        assertEquals("2026-08-23", MorningRateCachePolicy.periodKey(evening, vietnamTime))
        assertEquals("2026-08-23", MorningRateCachePolicy.periodKey(beforeMorningRefresh, vietnamTime))
        assertEquals("2026-08-24", MorningRateCachePolicy.periodKey(atMorningRefresh, vietnamTime))
    }

    @Test
    fun `cache boundary follows the device local time zone`() {
        val instant = localEpoch(
            year = 2026,
            month = Calendar.AUGUST,
            day = 24,
            hour = 0,
            minute = 30,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertEquals("2026-08-24", MorningRateCachePolicy.periodKey(instant, vietnamTime))
        assertEquals(
            "2026-08-23",
            MorningRateCachePolicy.periodKey(instant, TimeZone.getTimeZone("Europe/Moscow")),
        )
    }

    @Test
    fun `daylight saving change does not move the six o'clock boundary`() {
        val berlinTime = TimeZone.getTimeZone("Europe/Berlin")
        val beforeBoundary = localEpoch(2026, Calendar.MARCH, 29, 5, 59, berlinTime)
        val atBoundary = localEpoch(2026, Calendar.MARCH, 29, 6, 0, berlinTime)

        assertEquals("2026-03-28", MorningRateCachePolicy.periodKey(beforeBoundary, berlinTime))
        assertEquals("2026-03-29", MorningRateCachePolicy.periodKey(atBoundary, berlinTime))
    }

    @Test
    fun `invalid clock value is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MorningRateCachePolicy.periodKey(0L, vietnamTime)
        }
    }

    private fun localEpoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone,
    ): Long = GregorianCalendar(timeZone).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
