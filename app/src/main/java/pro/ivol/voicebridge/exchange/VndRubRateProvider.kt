package pro.ivol.voicebridge.exchange

import java.math.BigDecimal
import java.math.RoundingMode

/** Where the rate returned to the UI came from. */
enum class VndRubRateSource {
    CBR_NETWORK,
    DAILY_CACHE,
    LAST_KNOWN_CACHE,
    BUNDLED_LAST_KNOWN,
}

/**
 * One official CBR snapshot containing all quotes needed for VND conversion.
 * Every target quote keeps its published nominal because, for example, CBR
 * publishes UAH in batches of ten rather than one.
 *
 * [isStale] is true only when a network refresh failed and the last known quote
 * had to be used. A weekend/holiday quote returned by CBR itself is not marked
 * stale: [cbrEffectiveDate] still exposes its official effective date.
 */
data class VndRubRate(
    val nominalVnd: BigDecimal,
    val valueRub: BigDecimal,
    val nominalUsd: BigDecimal,
    val usdValueRub: BigDecimal,
    val nominalEur: BigDecimal,
    val eurValueRub: BigDecimal,
    val nominalUah: BigDecimal,
    val uahValueRub: BigDecimal,
    val cbrEffectiveDate: String,
    val fetchedAtEpochMillis: Long,
    val source: VndRubRateSource,
    val isStale: Boolean,
) {
    init {
        require(nominalVnd.signum() > 0) { "VND nominal must be positive." }
        require(valueRub.signum() > 0) { "VND value in RUB must be positive." }
        require(nominalUsd.signum() > 0) { "USD nominal must be positive." }
        require(usdValueRub.signum() > 0) { "USD value in RUB must be positive." }
        require(nominalEur.signum() > 0) { "EUR nominal must be positive." }
        require(eurValueRub.signum() > 0) { "EUR value in RUB must be positive." }
        require(nominalUah.signum() > 0) { "UAH nominal must be positive." }
        require(uahValueRub.signum() > 0) { "UAH value in RUB must be positive." }
        require(fetchedAtEpochMillis > 0L) { "Fetch time must be positive." }
        require(CBR_DATE_REGEX.matches(cbrEffectiveDate)) {
            "CBR effective date must use dd.MM.yyyy."
        }
    }

    val rublesPerVnd: BigDecimal
        get() = valueRub.divide(nominalVnd, UNIT_RATE_SCALE, RoundingMode.HALF_UP)

    val dollarsPerVnd: BigDecimal
        get() = valueRub
            .multiply(nominalUsd)
            .divide(
                nominalVnd.multiply(usdValueRub),
                UNIT_RATE_SCALE,
                RoundingMode.HALF_UP,
            )

    /** Converts from the original quotes, without using an already-rounded intermediate value. */
    fun convert(amountVnd: BigDecimal): VndRubConversion {
        require(amountVnd.signum() >= 0) { "VND amount cannot be negative." }
        val roundedRubles = amountVnd
            .multiply(valueRub)
            .divide(nominalVnd, 0, RoundingMode.HALF_UP)
        val roundedUsd = amountVnd
            .multiply(valueRub)
            .multiply(nominalUsd)
            .divide(
                nominalVnd.multiply(usdValueRub),
                TARGET_CURRENCY_SCALE,
                RoundingMode.HALF_UP,
            )
        val roundedEur = amountVnd
            .multiply(valueRub)
            .multiply(nominalEur)
            .divide(
                nominalVnd.multiply(eurValueRub),
                TARGET_CURRENCY_SCALE,
                RoundingMode.HALF_UP,
            )
        val roundedUah = amountVnd
            .multiply(valueRub)
            .multiply(nominalUah)
            .divide(
                nominalVnd.multiply(uahValueRub),
                TARGET_CURRENCY_SCALE,
                RoundingMode.HALF_UP,
            )
        return VndRubConversion(
            amountVnd = amountVnd,
            estimatedRubles = roundedRubles,
            estimatedUsd = roundedUsd,
            estimatedEur = roundedEur,
            estimatedUah = roundedUah,
            rate = this,
        )
    }

    private companion object {
        const val UNIT_RATE_SCALE = 12
        const val TARGET_CURRENCY_SCALE = 2
        val CBR_DATE_REGEX = Regex("(?:0[1-9]|[12][0-9]|3[01])\\.(?:0[1-9]|1[0-2])\\.\\d{4}")
    }
}

data class VndRubConversion(
    val amountVnd: BigDecimal,
    /** Whole roubles, rounded with HALF_UP. */
    val estimatedRubles: BigDecimal,
    /** US dollars, rounded to cents with HALF_UP. */
    val estimatedUsd: BigDecimal,
    /** Euros, rounded to cents with HALF_UP. */
    val estimatedEur: BigDecimal,
    /** Ukrainian hryvnias, rounded to kopecks with HALF_UP. */
    val estimatedUah: BigDecimal,
    val rate: VndRubRate,
) {
    /**
     * Applies an exchange-cost buffer to the exact CBR quote and rounds only
     * the final result. This avoids compounding a previously rounded amount.
     */
    fun estimatedRublesWithBuffer(bufferPercent: Int): BigDecimal {
        require(bufferPercent in 0..100) { "Buffer percent must be between 0 and 100." }
        val percentageMultiplier = BigDecimal.valueOf(100L + bufferPercent)
        val percentageDivisor = BigDecimal.valueOf(100L)
        return amountVnd
            .multiply(rate.valueRub)
            .multiply(percentageMultiplier)
            .divide(
                rate.nominalVnd.multiply(percentageDivisor),
                0,
                RoundingMode.HALF_UP,
            )
    }

    /** Applies the buffer to the exact VND/USD cross-rate and rounds only to cents. */
    fun estimatedUsdWithBuffer(bufferPercent: Int): BigDecimal {
        return estimatedTargetWithBuffer(
            targetNominal = rate.nominalUsd,
            targetValueRub = rate.usdValueRub,
            bufferPercent = bufferPercent,
        )
    }

    /** Applies the buffer to the exact VND/EUR cross-rate and rounds only to cents. */
    fun estimatedEurWithBuffer(bufferPercent: Int): BigDecimal {
        return estimatedTargetWithBuffer(
            targetNominal = rate.nominalEur,
            targetValueRub = rate.eurValueRub,
            bufferPercent = bufferPercent,
        )
    }

    /** Applies the buffer to the exact VND/UAH cross-rate and rounds only to kopecks. */
    fun estimatedUahWithBuffer(bufferPercent: Int): BigDecimal {
        return estimatedTargetWithBuffer(
            targetNominal = rate.nominalUah,
            targetValueRub = rate.uahValueRub,
            bufferPercent = bufferPercent,
        )
    }

    private fun estimatedTargetWithBuffer(
        targetNominal: BigDecimal,
        targetValueRub: BigDecimal,
        bufferPercent: Int,
    ): BigDecimal {
        require(bufferPercent in 0..100) { "Buffer percent must be between 0 and 100." }
        val percentageMultiplier = BigDecimal.valueOf(100L + bufferPercent)
        val percentageDivisor = BigDecimal.valueOf(100L)
        return amountVnd
            .multiply(rate.valueRub)
            .multiply(targetNominal)
            .multiply(percentageMultiplier)
            .divide(
                rate.nominalVnd
                    .multiply(targetValueRub)
                    .multiply(percentageDivisor),
                2,
                RoundingMode.HALF_UP,
            )
    }
}

interface VndRubRateProvider {
    /** Returns a verified saved or bundled quote immediately, without network access. */
    fun cachedRate(): VndRubRate? = null

    suspend fun latestRate(): Result<VndRubRate>

    suspend fun convert(amountVnd: BigDecimal): Result<VndRubConversion> =
        latestRate().mapCatching { rate -> rate.convert(amountVnd) }
}

/**
 * Last official CBR quote verified while building this APK.
 *
 * It is deliberately marked stale and is only a first-run fallback until a
 * current quote can be downloaded and persisted on the phone.
 */
internal object BundledCbrVndRate {
    val rate = VndRubRate(
        nominalVnd = BigDecimal("10000"),
        valueRub = BigDecimal("33.4247"),
        nominalUsd = BigDecimal("1"),
        usdValueRub = BigDecimal("85.6007"),
        nominalEur = BigDecimal("1"),
        eurValueRub = BigDecimal("99.6820"),
        nominalUah = BigDecimal("10"),
        uahValueRub = BigDecimal("19.2143"),
        cbrEffectiveDate = "29.08.2026",
        fetchedAtEpochMillis = 1_787_961_600_000L,
        source = VndRubRateSource.BUNDLED_LAST_KNOWN,
        isStale = true,
    )
}
