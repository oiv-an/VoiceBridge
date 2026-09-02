package pro.ivol.voicebridge.domain

data class LanguagePair(
    val sourceLanguage: AppLanguage = AppLanguage.RUSSIAN,
    val targetLanguage: AppLanguage = AppLanguage.VIETNAMESE,
) {
    init {
        require(sourceLanguage != targetLanguage) {
            "Configured source and target languages must be different."
        }
    }
}

interface LanguagePairStore {
    fun load(): LanguagePair

    fun save(languagePair: LanguagePair)
}
