package pro.ivol.voicebridge.exchange

import java.math.BigDecimal

internal data class CbrVndRateCacheEntry(
    val parsedRate: ParsedCbrVndRate,
    val fetchedAtEpochMillis: Long,
    /** Local 06:00-to-06:00 cache period in which this response was downloaded, yyyy-MM-dd. */
    val fetchDay: String,
) {
    init {
        require(fetchedAtEpochMillis > 0L) { "Fetch time must be positive." }
        require(DAY_REGEX.matches(fetchDay)) { "Invalid cache day." }
    }

    private companion object {
        val DAY_REGEX = Regex("\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])")
    }
}

/** Pure codec kept outside SharedPreferences so cache validation is JVM-testable. */
internal object CbrVndRateCacheCodec {
    private const val VERSION = "3"
    private val expectedKeys = setOf(
        "version",
        "nominalVnd",
        "valueRub",
        "nominalUsd",
        "usdValueRub",
        "nominalEur",
        "eurValueRub",
        "nominalUah",
        "uahValueRub",
        "effectiveDate",
        "fetchedAtEpochMillis",
        "fetchDay",
    )

    fun encode(entry: CbrVndRateCacheEntry): String = buildString {
        appendLine("version=$VERSION")
        appendLine("nominalVnd=${entry.parsedRate.nominalVnd.toPlainString()}")
        appendLine("valueRub=${entry.parsedRate.valueRub.toPlainString()}")
        appendLine("nominalUsd=${entry.parsedRate.nominalUsd.toPlainString()}")
        appendLine("usdValueRub=${entry.parsedRate.usdValueRub.toPlainString()}")
        appendLine("nominalEur=${entry.parsedRate.nominalEur.toPlainString()}")
        appendLine("eurValueRub=${entry.parsedRate.eurValueRub.toPlainString()}")
        appendLine("nominalUah=${entry.parsedRate.nominalUah.toPlainString()}")
        appendLine("uahValueRub=${entry.parsedRate.uahValueRub.toPlainString()}")
        appendLine("effectiveDate=${entry.parsedRate.effectiveDate}")
        appendLine("fetchedAtEpochMillis=${entry.fetchedAtEpochMillis}")
        appendLine("fetchDay=${entry.fetchDay}")
    }

    fun decode(encoded: String?): CbrVndRateCacheEntry? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val values = linkedMapOf<String, String>()
            encoded.lineSequence()
                .filter(String::isNotBlank)
                .forEach { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "Invalid cache line." }
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1)
                    require(values.put(key, value) == null) { "Duplicate cache key." }
                }
            require(values.keys == expectedKeys) { "Unexpected cache fields." }
            require(values.getValue("version") == VERSION) { "Unsupported cache version." }

            CbrVndRateCacheEntry(
                parsedRate = ParsedCbrVndRate(
                    nominalVnd = strictPositiveDecimal(values.getValue("nominalVnd")),
                    valueRub = strictPositiveDecimal(values.getValue("valueRub")),
                    nominalUsd = strictPositiveDecimal(values.getValue("nominalUsd")),
                    usdValueRub = strictPositiveDecimal(values.getValue("usdValueRub")),
                    nominalEur = strictPositiveDecimal(values.getValue("nominalEur")),
                    eurValueRub = strictPositiveDecimal(values.getValue("eurValueRub")),
                    nominalUah = strictPositiveDecimal(values.getValue("nominalUah")),
                    uahValueRub = strictPositiveDecimal(values.getValue("uahValueRub")),
                    effectiveDate = values.getValue("effectiveDate"),
                ),
                fetchedAtEpochMillis = values.getValue("fetchedAtEpochMillis")
                    .toLong()
                    .also { require(it > 0L) },
                fetchDay = values.getValue("fetchDay"),
            )
        }.getOrNull()
    }

    fun isFreshForPeriod(entry: CbrVndRateCacheEntry, currentPeriod: String): Boolean =
        entry.fetchDay == currentPeriod

    private fun strictPositiveDecimal(text: String): BigDecimal {
        require(DECIMAL_REGEX.matches(text)) { "Invalid cached decimal." }
        return text.toBigDecimal().also { require(it.signum() > 0) }
    }

    private val DECIMAL_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")
}
