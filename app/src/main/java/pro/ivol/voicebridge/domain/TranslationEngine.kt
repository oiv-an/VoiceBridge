package pro.ivol.voicebridge.domain

data class TranslationRequest(
    val text: String,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
)

interface TranslationEngine : AutoCloseable {
    val displayName: String

    /**
     * Engines own their threading policy. A local model should move expensive work
     * away from the main thread; a network implementation should suspend while waiting.
     */
    suspend fun translate(request: TranslationRequest): Result<String>

    override fun close() = Unit
}

/**
 * Intentional vertical-prototype seam. It leaves translation empty without showing
 * a false result until a real local or remote engine is connected.
 */
class PlaceholderTranslationEngine : TranslationEngine {
    override val displayName = "Translator unavailable"

    override suspend fun translate(request: TranslationRequest): Result<String> =
        Result.success("")
}
