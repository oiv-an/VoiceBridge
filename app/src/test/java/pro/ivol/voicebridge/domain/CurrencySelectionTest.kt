package pro.ivol.voicebridge.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencySelectionTest {
    @Test
    fun `default selection preserves RUB and USD`() {
        assertEquals(
            listOf(ConversionCurrency.RUB, ConversionCurrency.USD),
            CurrencySelection().orderedCurrencies,
        )
    }

    @Test
    fun `currencies can be added and removed but the last one remains`() {
        val rubOnly = CurrencySelection(setOf(ConversionCurrency.RUB))

        assertEquals(rubOnly, rubOnly.toggle(ConversionCurrency.RUB))
        assertEquals(
            setOf(ConversionCurrency.RUB, ConversionCurrency.EUR),
            rubOnly.toggle(ConversionCurrency.EUR).currencies,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty selection is rejected`() {
        CurrencySelection(emptySet())
    }
}
