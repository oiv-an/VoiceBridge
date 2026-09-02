package pro.ivol.voicebridge.exchange

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Divides local time into rate-cache periods that begin at 06:00.
 *
 * A quote fetched in the evening therefore remains fresh overnight, but the
 * first conversion at or after 06:00 local device time requests a new quote.
 */
internal object MorningRateCachePolicy {
    const val REFRESH_HOUR_LOCAL = 6

    fun periodKey(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        require(epochMillis > 0L) { "Current time must be positive." }

        val calendar = Calendar.getInstance(timeZone, Locale.ROOT).apply {
            timeInMillis = epochMillis
        }
        if (calendar.get(Calendar.HOUR_OF_DAY) < REFRESH_HOUR_LOCAL) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }

        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
