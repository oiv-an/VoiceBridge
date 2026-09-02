package pro.ivol.voicebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.ivol.voicebridge.domain.ConversionCurrency

class NumericInputDialogTest {
    @Test
    fun `price digits are grouped for quick reading`() {
        assertEquals("150 000", formatNumberForDisplay("150000"))
        assertEquals("1 250 000", formatNumberForDisplay("1250000"))
    }

    @Test
    fun `formatting ignores recognized words and separators`() {
        assertEquals("150 000", formatNumberForDisplay("Цена 150.000 донгов"))
    }

    @Test
    fun `US dollars keep cents only when they are meaningful`() {
        assertEquals("$0", formatUsdCentsForDisplay(0L))
        assertEquals("$0.04", formatUsdCentsForDisplay(4L))
        assertEquals("$6.50", formatUsdCentsForDisplay(650L))
        assertEquals("$625", formatUsdCentsForDisplay(62_500L))
        assertEquals("$1 234.56", formatUsdCentsForDisplay(123_456L))
    }

    @Test
    fun `all supported currencies use readable symbols and minor units`() {
        assertEquals("1 235 ₽", formatCurrencyMinorUnitsForDisplay(ConversionCurrency.RUB, 1_235L))
        assertEquals("€98.76", formatCurrencyMinorUnitsForDisplay(ConversionCurrency.EUR, 9_876L))
        assertEquals("432.10 ₴", formatCurrencyMinorUnitsForDisplay(ConversionCurrency.UAH, 43_210L))
    }

    @Test
    fun `selected estimates use stable currency order`() {
        val selected = linkedSetOf(
            ConversionCurrency.UAH,
            ConversionCurrency.RUB,
            ConversionCurrency.EUR,
        )
        val amounts = mapOf(
            ConversionCurrency.RUB to 1_235L,
            ConversionCurrency.EUR to 9_876L,
            ConversionCurrency.UAH to 43_210L,
        )

        assertEquals(
            "1 235 ₽  ·  €98.76  ·  432.10 ₴",
            formatSelectedCurrencyEstimates(selected, amounts::get),
        )
    }
}
