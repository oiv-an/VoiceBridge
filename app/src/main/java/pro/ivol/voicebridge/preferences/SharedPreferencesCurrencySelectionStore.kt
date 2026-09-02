package pro.ivol.voicebridge.preferences

import android.content.Context
import androidx.core.content.edit
import pro.ivol.voicebridge.domain.ConversionCurrency
import pro.ivol.voicebridge.domain.CurrencySelection
import pro.ivol.voicebridge.domain.CurrencySelectionStore

class SharedPreferencesCurrencySelectionStore(context: Context) : CurrencySelectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): CurrencySelection {
        val stored = preferences.getStringSet(SELECTED_CURRENCIES_KEY, null)
            ?: return CurrencySelection()
        val currencies = stored.mapNotNullTo(linkedSetOf()) { storedValue ->
            ConversionCurrency.entries.firstOrNull { it.name == storedValue }
        }
        return if (currencies.isEmpty()) CurrencySelection() else CurrencySelection(currencies)
    }

    override fun save(selection: CurrencySelection) {
        preferences.edit {
            putStringSet(
                SELECTED_CURRENCIES_KEY,
                selection.currencies.mapTo(linkedSetOf()) { currency -> currency.name },
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "conversion_currencies"
        const val SELECTED_CURRENCIES_KEY = "selected_currencies"
    }
}
