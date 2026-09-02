package pro.ivol.voicebridge.screen

import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Read-only access to the ML Kit translation models already owned by VoiceBridge.
 *
 * This class intentionally has no download method. Screen translation checks the
 * real ML Kit model store and either uses an installed model or reports that the
 * user must install the offline bundle from VoiceBridge settings.
 */
class ScreenTranslationModelManager internal constructor(
    private val remoteModelManager: RemoteModelManager,
) {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this(RemoteModelManager.getInstance())

    /** English is ML Kit's built-in pivot and has no downloadable remote model. */
    fun isModelDownloaded(languageCode: String): Boolean {
        ensureBackgroundThread()
        val normalizedCode = normalizeSupportedLanguageCode(languageCode)
        if (normalizedCode == TranslateLanguage.ENGLISH) return true
        return awaitModelTask(
            task = isModelDownloadedTask(normalizedCode),
            operation = "check the ${languageDisplayName(normalizedCode)} offline model",
        )
    }

    /** The overlay promises both English -> Russian and Vietnamese -> Russian. */
    fun areCoreModelsDownloaded(): Boolean {
        ensureBackgroundThread()
        return isModelDownloaded(TranslateLanguage.RUSSIAN) &&
            isModelDownloaded(TranslateLanguage.VIETNAMESE)
    }

    fun missingCoreModelNames(): List<String> {
        ensureBackgroundThread()
        return listOf(TranslateLanguage.RUSSIAN, TranslateLanguage.VIETNAMESE)
            .filterNot(::isModelDownloaded)
            .map(::languageDisplayName)
    }

    internal fun normalizeSupportedLanguageCode(code: String): String {
        val normalizedTag = code.trim()
        require(normalizedTag.isNotEmpty()) { "No source language was provided." }
        return TranslateLanguage.fromLanguageTag(normalizedTag)
            ?: throw IllegalArgumentException(
                "Language '$normalizedTag' is not supported by the local translator.",
            )
    }

    internal fun isModelDownloadedTask(code: String): Task<Boolean> {
        val normalizedCode = normalizeSupportedLanguageCode(code)
        if (normalizedCode == TranslateLanguage.ENGLISH) return Tasks.forResult(true)
        return remoteModelManager.isModelDownloaded(remoteModel(normalizedCode))
    }

    internal fun languageDisplayName(code: String): String {
        val normalizedCode = normalizeSupportedLanguageCode(code)
        return Locale.forLanguageTag(normalizedCode)
            .getDisplayLanguage(Locale.ENGLISH)
            .trim()
            .ifBlank { normalizedCode }
            .replaceFirstChar { first ->
                if (first.isLowerCase()) first.titlecase(Locale.ENGLISH) else first.toString()
            }
    }

    private fun remoteModel(code: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(normalizeSupportedLanguageCode(code)).build()

    private fun ensureBackgroundThread() {
        check(Looper.myLooper() == null || Looper.myLooper() != Looper.getMainLooper()) {
            "Offline model checks must run away from the main thread."
        }
    }

    private fun <T> awaitModelTask(task: Task<T>, operation: String): T = try {
        Tasks.await(task, MODEL_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ScreenTranslationModelException(
            "Could not $operation because the operation was cancelled.",
            interrupted,
        )
    } catch (timeout: TimeoutException) {
        throw ScreenTranslationModelException("Timed out while trying to $operation.", timeout)
    } catch (failure: ExecutionException) {
        throw ScreenTranslationModelException(
            message = "Could not $operation: ${
                failure.cause?.message?.takeIf(String::isNotBlank) ?: "unknown error"
            }.",
            cause = failure.cause ?: failure,
        )
    } catch (failure: Throwable) {
        throw ScreenTranslationModelException(
            message = "Could not $operation: ${
                failure.message?.takeIf(String::isNotBlank) ?: "unknown error"
            }.",
            cause = failure,
        )
    }

    private companion object {
        const val MODEL_CHECK_TIMEOUT_SECONDS = 10L
    }
}

class ScreenTranslationModelException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
