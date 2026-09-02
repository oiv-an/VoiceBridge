package pro.ivol.voicebridge.screen

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Fully on-device screen-text translation using VoiceBridge's installed ML Kit models.
 *
 * Translation deliberately never calls `downloadModelIfNeeded` or any model download API.
 * Language identification uses the model bundled in the APK. Source text, coordinates,
 * fingerprints, and translated text stay in memory for the duration of the request.
 */
class LocalScreenTranslationClient internal constructor(
    private val modelManager: ScreenTranslationModelManager,
    private val languageIdentifier: LanguageIdentifier,
) : AutoCloseable {
    constructor(context: Context) : this(
        modelManager = ScreenTranslationModelManager(context.applicationContext),
        languageIdentifier = createLanguageIdentifier(),
    )

    internal constructor(modelManager: ScreenTranslationModelManager) : this(
        modelManager = modelManager,
        languageIdentifier = createLanguageIdentifier(),
    )

    private val requestEpoch = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val requestLock = ReentrantLock()

    /** Allocates the immutable cancellation token before accessibility capture starts. */
    internal fun beginRequest(): Long = requestEpoch.incrementAndGet()

    fun areCoreModelsReady(): Boolean {
        ensureBackgroundThread()
        check(!closed.get()) { "The local screen translator is already closed." }
        return modelManager.areCoreModelsDownloaded()
    }

    fun missingCoreModelNames(): List<String> {
        ensureBackgroundThread()
        check(!closed.get()) { "The local screen translator is already closed." }
        return modelManager.missingCoreModelNames()
    }

    fun translate(
        elements: List<ScreenTextElement>,
        fallbackLanguageCode: String = TranslateLanguage.VIETNAMESE,
        deadlineElapsedRealtimeMs: Long =
            SystemClock.elapsedRealtime() + TOTAL_REQUEST_TIMEOUT_MILLIS,
        requestToken: Long? = null,
    ): TranslationResult {
        ensureBackgroundThread()
        check(!closed.get()) { "The local screen translator is already closed." }

        val epoch = requestToken ?: beginRequest()
        val remainingMillis = deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remainingMillis <= 0L) throw ScreenTranslationTimeoutException()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        val requestTranslators = mutableMapOf<String, Translator>()
        val lockWaitNanos = deadlineNanos - System.nanoTime()
        if (lockWaitNanos <= 0L) throw ScreenTranslationTimeoutException()
        val lockAcquired = try {
            requestLock.tryLock(lockWaitNanos, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Local screen translation was cancelled.")
        }
        if (!lockAcquired) throw ScreenTranslationTimeoutException()

        try {
            checkActive(epoch)
            checkDeadline(deadlineNanos)

            val fallback = modelManager.normalizeSupportedLanguageCode(fallbackLanguageCode)
            val preparedElements = elements.filter { it.source.isNotBlank() }
            if (preparedElements.isEmpty()) return TranslationResult(emptyList())

            val pageLanguage = identifyLanguageOrFallback(
                text = buildPageIdentificationText(preparedElements),
                fallbackLanguageCode = fallback,
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            )

            val checkedModels = mutableMapOf<String, Boolean>()
            requireDownloadedModel(
                languageCode = TARGET_LANGUAGE_CODE,
                checkedModels = checkedModels,
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            )
            if (pageLanguage != TARGET_LANGUAGE_CODE) {
                requireDownloadedModel(
                    languageCode = pageLanguage,
                    checkedModels = checkedModels,
                    epoch = epoch,
                    deadlineNanos = deadlineNanos,
                )
            }

            val detectedElements = ArrayList<DetectedElement>(preparedElements.size)
            for (element in preparedElements) {
                checkActive(epoch)
                checkDeadline(deadlineNanos)
                if (!element.source.any(Char::isLetter)) continue

                val detectedLanguageCode = if (
                    element.source.count(Char::isLetter) >=
                    MIN_ELEMENT_LETTERS_FOR_IDENTIFICATION
                ) {
                    identifyLanguageOrFallback(
                        text = element.source,
                        fallbackLanguageCode = pageLanguage,
                        epoch = epoch,
                        deadlineNanos = deadlineNanos,
                        failOnUnsupportedLanguage = false,
                    )
                } else {
                    pageLanguage
                }

                // Brand names and occasional false positives must not make an otherwise
                // supported page fail. Use a secondary language only when its model exists.
                val languageCode = if (
                    detectedLanguageCode != TARGET_LANGUAGE_CODE &&
                    detectedLanguageCode != pageLanguage &&
                    !isDownloadedModel(
                        languageCode = detectedLanguageCode,
                        checkedModels = checkedModels,
                        epoch = epoch,
                        deadlineNanos = deadlineNanos,
                    )
                ) {
                    pageLanguage
                } else {
                    detectedLanguageCode
                }

                if (languageCode != TARGET_LANGUAGE_CODE) {
                    detectedElements += DetectedElement(element, languageCode)
                }
            }

            if (detectedElements.isEmpty()) return TranslationResult(emptyList())

            val translatedByInput = mutableMapOf<TranslationCacheKey, String>()
            val blocks = ArrayList<TranslationBlock>(detectedElements.size)
            for ((element, languageCode) in detectedElements) {
                checkActive(epoch)
                checkDeadline(deadlineNanos)

                val cacheKey = TranslationCacheKey(languageCode, element.source)
                val translated = try {
                    translatedByInput[cacheKey] ?: translateText(
                        text = element.source,
                        sourceLanguageCode = languageCode,
                        requestTranslators = requestTranslators,
                        epoch = epoch,
                        deadlineNanos = deadlineNanos,
                    ).also { translatedText ->
                        translatedByInput[cacheKey] = translatedText
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (timeout: ScreenTranslationTimeoutException) {
                    if (blocks.isNotEmpty()) return TranslationResult(blocks)
                    throw timeout
                } catch (_: LocalScreenTranslationException) {
                    // One malformed or unsupported label must not discard the rest of the screen.
                    continue
                }

                blocks += TranslationBlock(
                    source = element.source,
                    translation = translated,
                    x = element.bounds.x,
                    y = element.bounds.y,
                    width = element.bounds.width,
                    height = element.bounds.height,
                    scrollGroupId = element.scrollGroupId,
                )
            }

            checkActive(epoch)
            return TranslationResult(blocks)
        } finally {
            requestTranslators.values.forEach { translator ->
                runCatching(translator::close)
            }
            requestTranslators.clear()
            requestLock.unlock()
        }
    }

    /** Invalidates the logical request; a late ML Kit callback is then ignored. */
    fun cancelActiveRequest() {
        requestEpoch.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestEpoch.incrementAndGet()
        requestLock.withLock {
            runCatching(languageIdentifier::close)
        }
    }

    private fun identifyLanguageOrFallback(
        text: String,
        fallbackLanguageCode: String,
        epoch: Long,
        deadlineNanos: Long,
        failOnUnsupportedLanguage: Boolean = true,
    ): String {
        if (text.count(Char::isLetter) < MIN_PAGE_LETTERS_FOR_IDENTIFICATION) {
            return fallbackLanguageCode
        }

        return try {
            val detectedTag = awaitTask(
                task = languageIdentifier.identifyLanguage(text),
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            )
            if (detectedTag == UNDETERMINED_LANGUAGE_TAG) {
                fallbackLanguageCode
            } else {
                try {
                    resolveIdentifiedLanguage(
                        detectedTag = detectedTag,
                        fallbackLanguageCode = fallbackLanguageCode,
                        failOnUnsupportedLanguage = failOnUnsupportedLanguage,
                        normalizeLanguageCode = modelManager::normalizeSupportedLanguageCode,
                    )
                } catch (unsupported: IllegalArgumentException) {
                    val languageName = java.util.Locale.forLanguageTag(detectedTag)
                        .getDisplayLanguage(java.util.Locale.ENGLISH)
                        .ifBlank { detectedTag }
                    throw LocalScreenTranslationException(
                        "Language '$languageName' is not supported by the local translator.",
                        unsupported,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: ScreenTranslationTimeoutException) {
            throw timeout
        } catch (unsupported: LocalScreenTranslationException) {
            throw unsupported
        } catch (_: Throwable) {
            checkActive(epoch)
            checkDeadline(deadlineNanos)
            fallbackLanguageCode
        }
    }

    private fun requireDownloadedModel(
        languageCode: String,
        checkedModels: MutableMap<String, Boolean>,
        epoch: Long,
        deadlineNanos: Long,
    ) {
        val normalizedCode = modelManager.normalizeSupportedLanguageCode(languageCode)
        if (
            !isDownloadedModel(
                languageCode = normalizedCode,
                checkedModels = checkedModels,
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            )
        ) {
            throw ScreenTranslationModelException(
                "The ${modelManager.languageDisplayName(normalizedCode)} offline model is not " +
                    "installed. Open VoiceBridge settings and download the offline models.",
            )
        }
    }

    private fun isDownloadedModel(
        languageCode: String,
        checkedModels: MutableMap<String, Boolean>,
        epoch: Long,
        deadlineNanos: Long,
    ): Boolean {
        val normalizedCode = modelManager.normalizeSupportedLanguageCode(languageCode)
        checkedModels[normalizedCode]?.let { return it }
        return try {
            awaitTask(
                task = modelManager.isModelDownloadedTask(normalizedCode),
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            ).also { downloaded -> checkedModels[normalizedCode] = downloaded }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: ScreenTranslationTimeoutException) {
            throw timeout
        } catch (failure: Throwable) {
            throw ScreenTranslationModelException(
                message = "Could not check the ${
                    modelManager.languageDisplayName(normalizedCode)
                } offline model.",
                cause = failure,
            )
        }
    }

    private fun translateText(
        text: String,
        sourceLanguageCode: String,
        requestTranslators: MutableMap<String, Translator>,
        epoch: Long,
        deadlineNanos: Long,
    ): String {
        val translator = translatorFor(sourceLanguageCode, requestTranslators)
        val translated = try {
            // No preparation/download call is made here. Missing models fail locally.
            awaitTask(
                task = translator.translate(text),
                epoch = epoch,
                deadlineNanos = deadlineNanos,
            ).trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: ScreenTranslationTimeoutException) {
            throw timeout
        } catch (failure: Throwable) {
            throw LocalScreenTranslationException(
                message = "Local screen translation is temporarily unavailable.",
                cause = failure,
            )
        }

        if (translated.isBlank()) {
            throw LocalScreenTranslationException("The local translator returned an empty result.")
        }
        return translated
    }

    private fun translatorFor(
        sourceLanguageCode: String,
        requestTranslators: MutableMap<String, Translator>,
    ): Translator {
        val normalizedSource = modelManager.normalizeSupportedLanguageCode(sourceLanguageCode)
        check(normalizedSource != TARGET_LANGUAGE_CODE) {
            "Russian text does not require translation."
        }
        return requestTranslators.getOrPut(normalizedSource) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(normalizedSource)
                    .setTargetLanguage(TARGET_LANGUAGE_CODE)
                    .build(),
            )
        }
    }

    private fun <T> awaitTask(
        task: Task<T>,
        epoch: Long,
        deadlineNanos: Long,
    ): T {
        while (true) {
            checkActive(epoch)
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw ScreenTranslationTimeoutException()

            val waitNanos = min(remainingNanos, CANCELLATION_POLL_NANOS)
            try {
                val result = Tasks.await(task, waitNanos, TimeUnit.NANOSECONDS)
                checkActive(epoch)
                checkDeadline(deadlineNanos)
                return result
            } catch (_: TimeoutException) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw ScreenTranslationTimeoutException()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Local screen translation was cancelled.")
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
    }

    private fun checkActive(epoch: Long) {
        if (closed.get()) throw CancellationException("The local translator is closed.")
        if (requestEpoch.get() != epoch || Thread.currentThread().isInterrupted) {
            throw CancellationException("Local screen translation was cancelled.")
        }
    }

    private fun checkDeadline(deadlineNanos: Long) {
        if (System.nanoTime() >= deadlineNanos) throw ScreenTranslationTimeoutException()
    }

    private fun ensureBackgroundThread() {
        check(Looper.myLooper() == null || Looper.myLooper() != Looper.getMainLooper()) {
            "Local screen translation must run away from the main thread."
        }
    }

    private fun buildPageIdentificationText(elements: List<ScreenTextElement>): String =
        buildString {
            for (element in elements) {
                if (length >= MAX_PAGE_IDENTIFICATION_CHARACTERS) break
                if (isNotEmpty()) append('\n')
                val remaining = MAX_PAGE_IDENTIFICATION_CHARACTERS - length
                append(element.source.take(remaining))
            }
        }

    private data class DetectedElement(
        val element: ScreenTextElement,
        val languageCode: String,
    )

    private data class TranslationCacheKey(
        val languageCode: String,
        val text: String,
    )

    private companion object {
        const val UNDETERMINED_LANGUAGE_TAG = "und"
        const val MIN_PAGE_LETTERS_FOR_IDENTIFICATION = 3
        const val MIN_ELEMENT_LETTERS_FOR_IDENTIFICATION = 12
        const val MAX_PAGE_IDENTIFICATION_CHARACTERS = 12_000
        const val TOTAL_REQUEST_TIMEOUT_MILLIS = 25_000L
        const val CANCELLATION_POLL_MILLIS = 100L
        val TARGET_LANGUAGE_CODE: String = TranslateLanguage.RUSSIAN
        val CANCELLATION_POLL_NANOS: Long =
            TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS)

        fun createLanguageIdentifier(): LanguageIdentifier =
            LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(0.35f)
                    .build(),
            )
    }
}

/**
 * Keeps dominant-page language detection strict while allowing a false-positive language on one
 * element to inherit the already validated page language. Cancellation, deadlines, and model
 * readiness remain the caller's responsibility and are deliberately not handled here.
 */
internal fun resolveIdentifiedLanguage(
    detectedTag: String,
    fallbackLanguageCode: String,
    failOnUnsupportedLanguage: Boolean,
    normalizeLanguageCode: (String) -> String,
): String = try {
    normalizeLanguageCode(detectedTag)
} catch (unsupported: IllegalArgumentException) {
    if (failOnUnsupportedLanguage) throw unsupported
    fallbackLanguageCode
}

open class LocalScreenTranslationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ScreenTranslationTimeoutException : LocalScreenTranslationException(
    "Screen translation did not finish within 25 seconds.",
)
