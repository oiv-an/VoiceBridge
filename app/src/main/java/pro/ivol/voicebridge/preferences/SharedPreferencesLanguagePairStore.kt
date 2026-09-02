package pro.ivol.voicebridge.preferences

import android.content.Context
import androidx.core.content.edit
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.LanguagePair
import pro.ivol.voicebridge.domain.LanguagePairStore

class SharedPreferencesLanguagePairStore(context: Context) : LanguagePairStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): LanguagePair {
        val defaults = LanguagePair()
        val source = preferences.readLanguage(SOURCE_LANGUAGE_KEY)
            ?: defaults.sourceLanguage
        val target = preferences.readLanguage(TARGET_LANGUAGE_KEY)
            ?: defaults.targetLanguage

        return if (source == target) defaults else LanguagePair(source, target)
    }

    override fun save(languagePair: LanguagePair) {
        preferences.edit {
            putString(SOURCE_LANGUAGE_KEY, languagePair.sourceLanguage.name)
            putString(TARGET_LANGUAGE_KEY, languagePair.targetLanguage.name)
        }
    }

    private fun android.content.SharedPreferences.readLanguage(key: String): AppLanguage? =
        getString(key, null)?.let { storedValue ->
            AppLanguage.entries.firstOrNull { language -> language.name == storedValue }
        }

    private companion object {
        const val PREFERENCES_NAME = "conversation_languages"
        const val SOURCE_LANGUAGE_KEY = "source_language"
        const val TARGET_LANGUAGE_KEY = "target_language"
    }
}
