package pro.ivol.voicebridge.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.TextSpeaker
import pro.ivol.voicebridge.domain.TextSpeakerEvent

/** Android [TextToSpeech] adapter whose mutable state is confined to the main thread. */
class AndroidTextSpeaker(context: Context) : TextSpeaker {
    private val appContext = context.applicationContext
    private val mainLooper = Looper.getMainLooper()
    private val mainHandler = Handler(mainLooper)
    private val nextUtteranceNumber = AtomicLong(0L)
    private val closeRequested = AtomicBoolean(false)

    private var listener: ((TextSpeakerEvent) -> Unit)? = null
    private var textToSpeech: TextToSpeech? = null
    private var state = State.INITIALIZING
    private var initializationGeneration = 0L
    private var initializationError = INITIALIZATION_ERROR
    private var pendingRequest: SpeakRequest? = null
    private var activeUtteranceId: String? = null
    private var activeEnginePackage: String? = null

    init {
        runOnMain { initialize(GOOGLE_TTS_ENGINE) }
    }

    override fun setListener(listener: (TextSpeakerEvent) -> Unit) {
        if (closeRequested.get()) return
        runOnMain {
            if (!closeRequested.get() && state != State.CLOSED) {
                this.listener = listener
            }
        }
    }

    override fun speak(text: String, language: AppLanguage): String {
        val utteranceId = "$UTTERANCE_ID_PREFIX${nextUtteranceNumber.incrementAndGet()}"
        if (closeRequested.get()) return utteranceId

        val request = SpeakRequest(
            utteranceId = utteranceId,
            text = text.trim(),
            language = language,
        )
        runOnMain { accept(request) }
        return utteranceId
    }

    override fun stop() {
        if (closeRequested.get()) return
        runOnMain(::stopOnMain)
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        runOnMain(::closeOnMain)
    }

    private fun initialize(requestedEnginePackage: String?) {
        if (closeRequested.get() || state == State.CLOSED) return

        val generation = ++initializationGeneration
        val engine = try {
            val onInitListener = TextToSpeech.OnInitListener { status ->
                // Always post: a vendor implementation is allowed to call back very
                // quickly, before the constructor result has been assigned below.
                mainHandler.post {
                    onInitialized(generation, status, requestedEnginePackage)
                }
            }
            if (requestedEnginePackage == null) {
                TextToSpeech(appContext, onInitListener)
            } else {
                TextToSpeech(appContext, onInitListener, requestedEnginePackage)
            }
        } catch (throwable: Throwable) {
            if (requestedEnginePackage == GOOGLE_TTS_ENGINE) {
                Log.w(TAG, "Google TTS is unavailable; trying the system TTS engine", throwable)
                initialize(requestedEnginePackage = null)
            } else {
                Log.e(TAG, "Unable to construct TextToSpeech", throwable)
                failInitialization(INITIALIZATION_ERROR)
            }
            return
        }

        if (closeRequested.get()) {
            safelyShutdown(engine)
            state = State.CLOSED
        } else {
            textToSpeech = engine
        }
    }

    private fun onInitialized(
        generation: Long,
        status: Int,
        requestedEnginePackage: String?,
    ) {
        if (
            closeRequested.get() ||
            state != State.INITIALIZING ||
            generation != initializationGeneration
        ) {
            return
        }

        val engine = textToSpeech ?: run {
            failInitialization(INITIALIZATION_ERROR)
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            if (requestedEnginePackage == GOOGLE_TTS_ENGINE) {
                Log.w(
                    TAG,
                    "Google TTS initialization failed with status=$status; " +
                        "trying the system TTS engine",
                )
                textToSpeech = null
                activeEnginePackage = null
                safelyShutdown(engine)
                initialize(requestedEnginePackage = null)
            } else {
                Log.e(TAG, "TextToSpeech initialization failed with status=$status")
                failInitialization(INITIALIZATION_ERROR, engine)
            }
            return
        }

        val listenerStatus = try {
            engine.setOnUtteranceProgressListener(progressListener())
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to install TextToSpeech progress listener", throwable)
            TextToSpeech.ERROR
        }
        if (listenerStatus != TextToSpeech.SUCCESS) {
            failInitialization(INITIALIZATION_ERROR, engine)
            return
        }

        activeEnginePackage = resolveEnginePackage(engine, requestedEnginePackage)
        Log.i(
            TAG,
            "TTS engine ready: requested=${requestedEnginePackage ?: SYSTEM_DEFAULT_ENGINE}, " +
                "active=${activeEngineName(engine)}",
        )
        state = State.READY
        pendingRequest?.also {
            pendingRequest = null
            speakNow(it, engine)
        }
    }

    private fun accept(request: SpeakRequest) {
        if (closeRequested.get() || state == State.CLOSED) return

        if (request.text.isBlank()) {
            dispatch(
                TextSpeakerEvent.Error(
                    utteranceId = request.utteranceId,
                    message = "There is no text to speak.",
                ),
            )
            return
        }

        when (state) {
            State.INITIALIZING -> pendingRequest = request
            State.READY -> {
                val engine = textToSpeech
                if (engine == null) {
                    failInitialization(INITIALIZATION_ERROR)
                    dispatch(TextSpeakerEvent.Error(request.utteranceId, INITIALIZATION_ERROR))
                } else {
                    speakNow(request, engine)
                }
            }

            State.FAILED -> dispatch(
                TextSpeakerEvent.Error(
                    utteranceId = request.utteranceId,
                    message = initializationError,
                ),
            )

            State.CLOSED -> Unit
        }
    }

    private fun speakNow(request: SpeakRequest, engine: TextToSpeech) {
        // Invalidate the old ID before stop(), so its asynchronous onStop callback
        // cannot overwrite the state for this newer QUEUE_FLUSH request.
        activeUtteranceId = null
        safelyStop(engine)

        val requestedLocale = Locale.forLanguageTag(request.language.languageTag)
        val voiceChoice = chooseVoice(engine, requestedLocale)
        if (voiceChoice == null) {
            Log.w(
                TAG,
                "No compatible TTS voice: engine=${activeEngineName(engine)}, " +
                    "requestedLocale=${requestedLocale.toLanguageTag()}",
            )
            dispatch(
                TextSpeakerEvent.Error(
                    request.utteranceId,
                    voiceUnavailableError(request.language),
                ),
            )
            return
        }

        val voiceStatus = try {
            engine.setVoice(voiceChoice.voice)
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "Unable to select TTS voice for locale=${requestedLocale.toLanguageTag()}",
                throwable,
            )
            dispatch(
                TextSpeakerEvent.Error(
                    request.utteranceId,
                    voiceUnavailableError(request.language),
                ),
            )
            return
        }

        if (voiceStatus != TextToSpeech.SUCCESS) {
            dispatch(
                TextSpeakerEvent.Error(
                    request.utteranceId,
                    voiceUnavailableError(request.language),
                ),
            )
            return
        }

        val activeVoice = runCatching { engine.voice }
            .onFailure {
                Log.e(
                    TAG,
                    "Unable to verify TTS voice for locale=${requestedLocale.toLanguageTag()}",
                    it,
                )
            }
            .getOrNull()
        if (activeVoice == null || !isSameLanguage(activeVoice.locale, requestedLocale)) {
            Log.e(
                TAG,
                "TTS engine selected an incompatible voice: " +
                    "engine=${activeEngineName(engine)}, " +
                    "requestedLocale=${requestedLocale.toLanguageTag()}, " +
                    "activeVoiceLocale=${activeVoice?.locale?.toLanguageTag() ?: NO_VOICE}",
            )
            dispatch(
                TextSpeakerEvent.Error(
                    request.utteranceId,
                    voiceUnavailableError(request.language),
                ),
            )
            return
        }

        Log.i(
            TAG,
            "TTS voice selected: engine=${activeEngineName(engine)}, " +
                "voiceLocale=${activeVoice.locale.toLanguageTag()}, " +
                "exactLocale=${isExactLocale(activeVoice.locale, requestedLocale)}",
        )

        activeUtteranceId = request.utteranceId
        val result = try {
            engine.speak(
                request.text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                request.utteranceId,
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "TextToSpeech.speak failed", throwable)
            TextToSpeech.ERROR
        }

        if (result != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            dispatch(
                TextSpeakerEvent.Error(
                    request.utteranceId,
                    "Could not start speech playback.",
                ),
            )
        }
    }

    private fun progressListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            runOnMain { onUtteranceStarted(utteranceId) }
        }

        override fun onDone(utteranceId: String) {
            runOnMain { onUtteranceDone(utteranceId) }
        }

        @Deprecated("Android callback without a detailed error code")
        override fun onError(utteranceId: String) {
            runOnMain { onUtteranceError(utteranceId, null) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            runOnMain { onUtteranceError(utteranceId, errorCode) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            runOnMain { onUtteranceStopped(utteranceId) }
        }
    }

    private fun onUtteranceStarted(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        dispatch(TextSpeakerEvent.Started(utteranceId))
    }

    private fun onUtteranceDone(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        activeUtteranceId = null
        dispatch(TextSpeakerEvent.Done(utteranceId))
    }

    private fun onUtteranceError(utteranceId: String, errorCode: Int?) {
        if (!isCurrent(utteranceId)) return
        activeUtteranceId = null
        dispatch(
            TextSpeakerEvent.Error(
                utteranceId = utteranceId,
                message = utteranceError(errorCode),
            ),
        )
    }

    private fun onUtteranceStopped(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        activeUtteranceId = null
        dispatch(
            TextSpeakerEvent.Error(
                utteranceId = utteranceId,
                message = "Speech playback was interrupted.",
            ),
        )
    }

    private fun stopOnMain() {
        pendingRequest = null
        activeUtteranceId = null
        if (state == State.READY) textToSpeech?.let(::safelyStop)
    }

    private fun closeOnMain() {
        pendingRequest = null
        activeUtteranceId = null
        initializationGeneration += 1
        state = State.CLOSED
        listener = null

        val engine = textToSpeech
        textToSpeech = null
        activeEnginePackage = null
        if (engine != null) {
            safelyStop(engine)
            safelyShutdown(engine)
        }
    }

    private fun failInitialization(
        message: String,
        engine: TextToSpeech? = textToSpeech,
    ) {
        state = State.FAILED
        initializationError = message
        textToSpeech = null
        activeUtteranceId = null
        activeEnginePackage = null
        engine?.let(::safelyShutdown)

        pendingRequest?.also { request ->
            pendingRequest = null
            dispatch(TextSpeakerEvent.Error(request.utteranceId, message))
        }
    }

    private fun safelyStop(engine: TextToSpeech) {
        runCatching { engine.stop() }
            .onFailure { Log.w(TAG, "TextToSpeech.stop failed", it) }
    }

    private fun safelyShutdown(engine: TextToSpeech) {
        runCatching { engine.shutdown() }
            .onFailure { Log.w(TAG, "TextToSpeech.shutdown failed", it) }
    }

    private fun isCurrent(utteranceId: String): Boolean =
        !closeRequested.get() &&
            state == State.READY &&
            activeUtteranceId == utteranceId

    /** Kept defensive so future callers cannot accidentally deliver events off-main. */
    private fun dispatch(event: TextSpeakerEvent) {
        if (Looper.myLooper() != mainLooper) {
            mainHandler.post { dispatch(event) }
            return
        }
        if (!closeRequested.get() && state != State.CLOSED) listener?.invoke(event)
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == mainLooper) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun chooseVoice(
        engine: TextToSpeech,
        requestedLocale: Locale,
    ): VoiceChoice? {
        val availableVoices = try {
            engine.voices.orEmpty()
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "Unable to inspect TTS voices: engine=${activeEngineName(engine)}, " +
                    "requestedLocale=${requestedLocale.toLanguageTag()}",
                throwable,
            )
            return null
        }

        return availableVoices.asSequence()
            .filter { voice ->
                isSameLanguage(voice.locale, requestedLocale) &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
            .map { voice ->
                VoiceChoice(
                    voice = voice,
                    exactLocale = isExactLocale(voice.locale, requestedLocale),
                )
            }
            .sortedWith(
                compareByDescending<VoiceChoice> { it.exactLocale }
                    .thenBy { it.voice.isNetworkConnectionRequired }
                    .thenByDescending { it.voice.quality }
                    .thenBy { it.voice.latency }
                    .thenBy { it.voice.name },
            )
            .firstOrNull()
    }

    private fun resolveEnginePackage(
        engine: TextToSpeech,
        requestedEnginePackage: String?,
    ): String = requestedEnginePackage
        ?.takeIf { requested ->
            runCatching { engine.engines.any { it.name == requested } }.getOrDefault(false)
        }
        ?: engine.defaultEngine
        ?: UNKNOWN_ENGINE

    private fun activeEngineName(engine: TextToSpeech): String =
        activeEnginePackage ?: engine.defaultEngine ?: UNKNOWN_ENGINE

    private fun isExactLocale(actual: Locale, requested: Locale): Boolean =
        actual.toLanguageTag().equals(requested.toLanguageTag(), ignoreCase = true)

    private fun isSameLanguage(actual: Locale, requested: Locale): Boolean =
        requested.language.isNotBlank() &&
            actual.language.equals(requested.language, ignoreCase = true)

    private fun voiceUnavailableError(language: AppLanguage): String =
        "No ${language.displayName} text-to-speech voice is installed. " +
            "Install or enable one in Android text-to-speech settings."

    private fun utteranceError(errorCode: Int?): String = when (errorCode) {
        TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            "The speech service timed out while waiting for the network."
        TextToSpeech.ERROR_NETWORK -> "Network error during speech playback."
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "Voice data for the selected language is not installed yet."
        TextToSpeech.ERROR_OUTPUT -> "Audio output error."
        TextToSpeech.ERROR_SERVICE -> "The Android speech service is unavailable."
        TextToSpeech.ERROR_SYNTHESIS -> "Could not synthesize speech."
        TextToSpeech.ERROR_INVALID_REQUEST -> "The speech service rejected the request."
        null -> "Could not speak the text."
        else -> "Speech playback error (code $errorCode)."
    }

    private data class SpeakRequest(
        val utteranceId: String,
        val text: String,
        val language: AppLanguage,
    )

    private data class VoiceChoice(
        val voice: Voice,
        val exactLocale: Boolean,
    )

    private enum class State {
        INITIALIZING,
        READY,
        FAILED,
        CLOSED,
    }

    private companion object {
        const val TAG = "VoiceBridgeTTS"
        const val UTTERANCE_ID_PREFIX = "voicebridge-tts-"
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        const val SYSTEM_DEFAULT_ENGINE = "system-default"
        const val UNKNOWN_ENGINE = "unknown"
        const val NO_VOICE = "none"
        const val INITIALIZATION_ERROR =
            "Could not start the Android speech service. " +
                "Check that a text-to-speech engine is installed."
    }
}
