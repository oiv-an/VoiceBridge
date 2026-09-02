package pro.ivol.voicebridge.domain

enum class ConversionCurrency(
    val code: String,
    val symbol: String,
    val fractionDigits: Int,
) {
    RUB(code = "RUB", symbol = "₽", fractionDigits = 0),
    USD(code = "USD", symbol = "\$", fractionDigits = 2),
    EUR(code = "EUR", symbol = "€", fractionDigits = 2),
    UAH(code = "UAH", symbol = "₴", fractionDigits = 2),
}

data class CurrencySelection(
    val currencies: Set<ConversionCurrency> = DEFAULT_CURRENCIES,
) {
    init {
        require(currencies.isNotEmpty()) { "At least one conversion currency must be selected." }
    }

    fun toggle(currency: ConversionCurrency): CurrencySelection {
        if (currency in currencies && currencies.size == 1) return this
        val updated = currencies.toMutableSet().apply {
            if (!remove(currency)) add(currency)
        }
        return CurrencySelection(updated)
    }

    val orderedCurrencies: List<ConversionCurrency>
        get() = ConversionCurrency.entries.filter(currencies::contains)

    companion object {
        val DEFAULT_CURRENCIES: Set<ConversionCurrency> =
            setOf(ConversionCurrency.RUB, ConversionCurrency.USD)
    }
}

interface CurrencySelectionStore {
    fun load(): CurrencySelection

    fun save(selection: CurrencySelection)
}
