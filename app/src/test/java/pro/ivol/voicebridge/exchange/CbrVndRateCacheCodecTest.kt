package pro.ivol.voicebridge.exchange

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CbrVndRateCacheCodecTest {
    @Test
    fun `cache entry survives a strict encode decode round trip`() {
        val original = entry()
        val encoded = CbrVndRateCacheCodec.encode(original)

        val decoded = CbrVndRateCacheCodec.decode(encoded)

        assertTrue(encoded.startsWith("version=3\n"))
        assertTrue(encoded.contains("nominalUsd=1\n"))
        assertTrue(encoded.contains("usdValueRub=80.0000\n"))
        assertTrue(encoded.contains("nominalEur=1\n"))
        assertTrue(encoded.contains("nominalUah=10\n"))
        assertEquals(original, decoded)
    }

    @Test
    fun `same morning cache period is fresh and another period is stale`() {
        val cached = entry(fetchDay = "2026-08-23")

        assertTrue(CbrVndRateCacheCodec.isFreshForPeriod(cached, "2026-08-23"))
        assertFalse(CbrVndRateCacheCodec.isFreshForPeriod(cached, "2026-08-24"))
    }

    @Test
    fun `corrupt unsupported or incomplete cache is ignored`() {
        assertNull(CbrVndRateCacheCodec.decode(null))
        assertNull(CbrVndRateCacheCodec.decode("version=1\n"))
        assertNull(
            CbrVndRateCacheCodec.decode(
                CbrVndRateCacheCodec.encode(entry()).replace("version=3", "version=2"),
            ),
        )
        assertNull(
            CbrVndRateCacheCodec.decode(
                CbrVndRateCacheCodec.encode(entry()) + "unexpected=value\n",
            ),
        )
        assertNull(
            CbrVndRateCacheCodec.decode(
                CbrVndRateCacheCodec.encode(entry()).replace("valueRub=30.5584", "valueRub=NaN"),
            ),
        )
        assertNull(
            CbrVndRateCacheCodec.decode(
                CbrVndRateCacheCodec.encode(entry()).replace("usdValueRub=80.0000", "usdValueRub=0"),
            ),
        )
    }

    @Test
    fun `conversion uses exact quote and rounds half up to whole roubles`() {
        val rate = VndRubRate(
            nominalVnd = BigDecimal("10000"),
            valueRub = BigDecimal("30.555"),
            nominalUsd = BigDecimal("1"),
            usdValueRub = BigDecimal("80.0000"),
            nominalEur = BigDecimal("1"),
            eurValueRub = BigDecimal("100.0000"),
            nominalUah = BigDecimal("10"),
            uahValueRub = BigDecimal("20.0000"),
            cbrEffectiveDate = "23.08.2026",
            fetchedAtEpochMillis = 1_777_000_000_000L,
            source = VndRubRateSource.CBR_NETWORK,
            isStale = false,
        )

        val conversion = rate.convert(BigDecimal("1000000"))

        assertEquals(BigDecimal("3056"), conversion.estimatedRubles)
        assertEquals(BigDecimal("38.19"), conversion.estimatedUsd)
        assertEquals(rate, conversion.rate)
        assertEquals(BigDecimal("0.003055500000"), rate.rublesPerVnd)
    }

    @Test
    fun `conversion rejects negative amount`() {
        val rate = entry().toRate()

        assertThrows(IllegalArgumentException::class.java) {
            rate.convert(BigDecimal("-1"))
        }
    }

    @Test
    fun `exchange buffer is applied before the final rounding`() {
        val rate = VndRubRate(
            nominalVnd = BigDecimal("10000"),
            valueRub = BigDecimal("30.555"),
            nominalUsd = BigDecimal("1"),
            usdValueRub = BigDecimal("80.0000"),
            nominalEur = BigDecimal("1"),
            eurValueRub = BigDecimal("100.0000"),
            nominalUah = BigDecimal("10"),
            uahValueRub = BigDecimal("20.0000"),
            cbrEffectiveDate = "23.08.2026",
            fetchedAtEpochMillis = 1_777_000_000_000L,
            source = VndRubRateSource.CBR_NETWORK,
            isStale = false,
        )

        val conversion = rate.convert(BigDecimal("1000000"))

        assertEquals(BigDecimal("3056"), conversion.estimatedRubles)
        assertEquals(BigDecimal("3208"), conversion.estimatedRublesWithBuffer(5))
        assertEquals(BigDecimal("38.19"), conversion.estimatedUsd)
        assertEquals(BigDecimal("40.10"), conversion.estimatedUsdWithBuffer(5))
    }

    @Test
    fun `USD rounds half up only after applying the buffer to the exact cross rate`() {
        val rate = syntheticRate(nominalVnd = "10000", nominalUsd = "1", usdValueRub = "1")

        val halfCent = rate.convert(BigDecimal("10050"))
        val bufferSensitive = rate.convert(BigDecimal("10149"))

        assertEquals(BigDecimal("1.01"), halfCent.estimatedUsd)
        assertEquals(BigDecimal("1.01"), bufferSensitive.estimatedUsd)
        assertEquals(BigDecimal("1.07"), bufferSensitive.estimatedUsdWithBuffer(5))
    }

    @Test
    fun `USD nominal participates in the cross rate`() {
        val rate = syntheticRate(nominalVnd = "1000", nominalUsd = "10", usdValueRub = "20")

        assertEquals(BigDecimal("0.50"), rate.convert(BigDecimal("1000")).estimatedUsd)
    }

    @Test
    fun `UAH nominal participates in the cross rate`() {
        val rate = syntheticRate(
            nominalVnd = "1000",
            nominalUsd = "1",
            usdValueRub = "1",
        )

        assertEquals(BigDecimal("0.50"), rate.convert(BigDecimal("1000")).estimatedUah)
    }

    @Test
    fun `bundled first run fallback is the verified CBR quote and is marked stale`() {
        val rate = BundledCbrVndRate.rate

        assertEquals(BigDecimal("10000"), rate.nominalVnd)
        assertEquals(BigDecimal("33.4247"), rate.valueRub)
        assertEquals(BigDecimal("1"), rate.nominalUsd)
        assertEquals(BigDecimal("85.6007"), rate.usdValueRub)
        assertEquals(BigDecimal("1"), rate.nominalEur)
        assertEquals(BigDecimal("99.6820"), rate.eurValueRub)
        assertEquals(BigDecimal("10"), rate.nominalUah)
        assertEquals(BigDecimal("19.2143"), rate.uahValueRub)
        assertEquals("29.08.2026", rate.cbrEffectiveDate)
        assertEquals(VndRubRateSource.BUNDLED_LAST_KNOWN, rate.source)
        assertTrue(rate.isStale)
        assertEquals(
            BigDecimal("17548"),
            rate.convert(BigDecimal("5000000")).estimatedRublesWithBuffer(5),
        )
        assertEquals(
            BigDecimal("205.00"),
            rate.convert(BigDecimal("5000000")).estimatedUsdWithBuffer(5),
        )
        assertEquals(
            BigDecimal("176.04"),
            rate.convert(BigDecimal("5000000")).estimatedEurWithBuffer(5),
        )
        assertEquals(
            BigDecimal("9132.76"),
            rate.convert(BigDecimal("5000000")).estimatedUahWithBuffer(5),
        )
    }

    private fun entry(fetchDay: String = "2026-08-23") = CbrVndRateCacheEntry(
        parsedRate = ParsedCbrVndRate(
            nominalVnd = BigDecimal("10000"),
            valueRub = BigDecimal("30.5584"),
            nominalUsd = BigDecimal("1"),
            usdValueRub = BigDecimal("80.0000"),
            nominalEur = BigDecimal("1"),
            eurValueRub = BigDecimal("100.0000"),
            nominalUah = BigDecimal("10"),
            uahValueRub = BigDecimal("20.0000"),
            effectiveDate = "23.08.2026",
        ),
        fetchedAtEpochMillis = 1_777_000_000_000L,
        fetchDay = fetchDay,
    )

    private fun CbrVndRateCacheEntry.toRate() = VndRubRate(
        nominalVnd = parsedRate.nominalVnd,
        valueRub = parsedRate.valueRub,
        nominalUsd = parsedRate.nominalUsd,
        usdValueRub = parsedRate.usdValueRub,
        nominalEur = parsedRate.nominalEur,
        eurValueRub = parsedRate.eurValueRub,
        nominalUah = parsedRate.nominalUah,
        uahValueRub = parsedRate.uahValueRub,
        cbrEffectiveDate = parsedRate.effectiveDate,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        source = VndRubRateSource.DAILY_CACHE,
        isStale = false,
    )

    private fun syntheticRate(
        nominalVnd: String,
        nominalUsd: String,
        usdValueRub: String,
    ) = VndRubRate(
        nominalVnd = BigDecimal(nominalVnd),
        valueRub = BigDecimal("1"),
        nominalUsd = BigDecimal(nominalUsd),
        usdValueRub = BigDecimal(usdValueRub),
        nominalEur = BigDecimal("1"),
        eurValueRub = BigDecimal("1"),
        nominalUah = BigDecimal("10"),
        uahValueRub = BigDecimal("20"),
        cbrEffectiveDate = "23.08.2026",
        fetchedAtEpochMillis = 1_777_000_000_000L,
        source = VndRubRateSource.CBR_NETWORK,
        isStale = false,
    )
}
