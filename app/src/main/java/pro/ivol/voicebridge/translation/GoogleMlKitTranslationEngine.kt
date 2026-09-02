package pro.ivol.voicebridge.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.TranslationEngine
import pro.ivol.voicebridge.domain.TranslationRequest

/** Google ML Kit downloads the selected language models once, then translates locally. */
class GoogleMlKitTranslationEngine : TranslationEngine {
    override val displayName = "Google Translate / ML Kit (offline after download)"

    private val lock = Any()
    private val translators = mutableMapOf<LanguagePair, Translator>()
    private val preparationTasks = mutableMapOf<LanguagePair, Task<Void>>()
    private var closed = false

    override suspend fun translate(request: TranslationRequest): Result<String> =
        try {
            check(!closed) { "The translator is already closed." }
            val pair = LanguagePair(request.sourceLanguage, request.targetLanguage)
            val translator = translatorFor(pair)
            prepare(pair, translator)
            Result.success(translator.translate(request.text).awaitResult())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }

    override fun close() {
        val clients = synchronized(lock) {
            if (closed) return
            closed = true
            preparationTasks.clear()
            translators.values.toList().also { translators.clear() }
        }
        clients.forEach(Translator::close)
    }

    private fun translatorFor(pair: LanguagePair): Translator = synchronized(lock) {
        check(!closed) { "The translator is already closed." }
        translators.getOrPut(pair) {
            val source = TranslateLanguage.fromLanguageTag(pair.source.isoCode)
                ?: error("${pair.source.displayName} is not supported by the translator.")
            val target = TranslateLanguage.fromLanguageTag(pair.target.isoCode)
                ?: error("${pair.target.displayName} is not supported by the translator.")

            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }
    }

    private suspend fun prepare(pair: LanguagePair, translator: Translator) {
        val task = synchronized(lock) {
            check(!closed) { "The translator is already closed." }
            preparationTasks[pair] ?: translator
                .downloadModelIfNeeded(DownloadConditions.Builder().build())
                .also { createdTask ->
                    preparationTasks[pair] = createdTask
                    createdTask.addOnCompleteListener { completedTask ->
                        if (!completedTask.isSuccessful) {
                            synchronized(lock) {
                                if (preparationTasks[pair] === createdTask) {
                                    preparationTasks.remove(pair)
                                }
                            }
                        }
                    }
                }
            }

        try {
            task.awaitResult()
        } catch (cancellation: CancellationException) {
            // Coroutine cancellation does not cancel a Google Task. Keep a still
            // running shared download so the next partial result can reuse it.
            if (task.isCanceled || (task.isComplete && !task.isSuccessful)) {
                synchronized(lock) {
                    if (preparationTasks[pair] === task) preparationTasks.remove(pair)
                }
            }
            throw cancellation
        } catch (throwable: Throwable) {
            synchronized(lock) {
                if (preparationTasks[pair] === task) preparationTasks.remove(pair)
            }
            throw throwable
        }
    }

    private val AppLanguage.isoCode: String
        get() = Locale.forLanguageTag(languageTag).language

    private data class LanguagePair(
        val source: AppLanguage,
        val target: AppLanguage,
    )
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) continuation.resumeWithException(throwable)
        }
        addOnCanceledListener { continuation.cancel() }
    }
