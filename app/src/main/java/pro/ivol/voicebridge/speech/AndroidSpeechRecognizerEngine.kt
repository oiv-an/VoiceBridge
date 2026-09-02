package pro.ivol.voicebridge.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent

/**
 * Android's online recognizer normally treats a pause as the end of a request.
 *
 * A push-to-talk press is therefore a logical session made up of one or more fresh
 * [SpeechRecognizer] cycles. Natural endpointing commits the current segment and
 * starts another cycle while the button remains held. Only an explicit
 * [stopListening] ends the logical session and emits Processing/FinalResult.
 */
class AndroidSpeechRecognizerEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferredService = findPreferredRecognitionService()
    private val transcript = SpeechSegmentAccumulator()
    private val isHuaweiDevice =
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.BRAND.equals("HUAWEI", ignoreCase = true)

    override val displayName: String = when (preferredService?.packageName) {
        GOOGLE_APP_PACKAGE -> "Google Speech (online)"
        GOOGLE_TTS_PACKAGE -> "Google Speech Services"
        null -> "Speech recognition service not found"
        else -> "Android Speech: ${preferredService.packageName}"
    }

    private var recognizer: SpeechRecognizer? = null
    private var eventListener: ((SpeechEvent) -> Unit)? = null
    private var phase = Phase.IDLE

    private var sessionSequence = 0L
    private var activeSession = NO_SESSION
    private var cycleSequence = 0L
    private var activeCycle = NO_CYCLE

    private var activeLanguageTag = ""
    private var currentCyclePartial = ""
    private var buttonHeld = false
    private var releaseRequested = false
    private var consecutiveEmptyCycles = 0

    private var startupTimeout: Runnable? = null
    private var resultTimeout: Runnable? = null
    private var pendingRestart: Runnable? = null

    @Volatile
    private var destroyed = false

    override fun setListener(listener: (SpeechEvent) -> Unit) {
        if (!destroyed) eventListener = listener
    }

    override fun startListening(languageTag: String) {
        runOnMain {
            if (destroyed) return@runOnMain

            val service = preferredService
            if (service == null) {
                dispatch(
                    SpeechEvent.Error(
                        if (isHuaweiDevice) {
                            "No working speech recognition service was found on this Huawei device."
                        } else {
                            "No speech recognition service is available on this device."
                        },
                    ),
                )
                return@runOnMain
            }

            releaseActiveSession()

            val session = ++sessionSequence
            activeSession = session
            activeLanguageTag = languageTag
            buttonHeld = true
            releaseRequested = false
            consecutiveEmptyCycles = 0
            transcript.clear()

            startRecognizerCycle(session, service)
        }
    }

    override fun stopListening() {
        runOnMain {
            val session = activeSession
            if (session == NO_SESSION) {
                dispatch(SpeechEvent.Stopped)
                return@runOnMain
            }
            if (releaseRequested) return@runOnMain

            buttonHeld = false
            releaseRequested = true
            cancelPendingRestart()
            dispatch(SpeechEvent.Processing)

            val cycle = activeCycle
            val activeRecognizer = recognizer
            if (cycle == NO_CYCLE || activeRecognizer == null) {
                finishReleasedSession(
                    session = session,
                    emptyMessage = "Speech was not recognized. Please try again.",
                )
                return@runOnMain
            }

            // Once Android has endpointed, stopListening() is no longer valid. Wait
            // for that cycle's pending result and finalize it instead.
            if (phase == Phase.AWAITING_RESULT) {
                scheduleResultTimeout(session, cycle)
                return@runOnMain
            }

            phase = Phase.STOPPING
            cancelStartupTimeout()
            scheduleResultTimeout(session, cycle)
            runCatching { activeRecognizer.stopListening() }
                .onFailure { throwable ->
                    Log.e(TAG, "stopListening failed", throwable)
                    finishReleasedSession(
                        session = session,
                        segment = currentCyclePartial,
                        emptyMessage = throwable.message
                            ?: "Could not finish speech recognition.",
                    )
                }
        }
    }

    override fun cancelListening() {
        runOnMain {
            releaseActiveSession()
            dispatch(SpeechEvent.Stopped)
        }
    }

    override fun destroy() {
        runOnMain {
            if (destroyed) return@runOnMain
            destroyed = true
            releaseActiveSession()
            eventListener = null
        }
    }

    private fun startRecognizerCycle(
        session: Long,
        service: ComponentName? = preferredService,
    ) {
        if (!isCurrentSession(session) || !buttonHeld || releaseRequested) return
        val selectedService = service ?: return

        cancelPendingRestart()
        releaseCurrentCycle()

        val cycle = ++cycleSequence
        activeCycle = cycle
        currentCyclePartial = ""
        phase = Phase.STARTING

        var createdRecognizer: SpeechRecognizer? = null
        val activeRecognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext, selectedService).also {
                createdRecognizer = it
                it.setRecognitionListener(listenerFor(session, cycle))
            }
        }.onFailure { throwable ->
            Log.e(
                TAG,
                "Failed to create explicit recognition service: $selectedService",
                throwable,
            )
            createdRecognizer?.let(::releaseRecognizer)
        }.getOrNull()

        if (activeRecognizer == null) {
            clearFailedCycle(cycle)
            finishWith(
                session,
                SpeechEvent.Error("Could not connect to the speech recognition service."),
            )
            return
        }

        recognizer = activeRecognizer
        scheduleStartupTimeout(session, cycle)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        Log.i(
            TAG,
            "startListening session=$session cycle=$cycle " +
                "language=$activeLanguageTag service=$selectedService",
        )
        runCatching { activeRecognizer.startListening(intent) }
            .onFailure { throwable ->
                Log.e(TAG, "startListening failed session=$session cycle=$cycle", throwable)
                finishWith(
                    session,
                    SpeechEvent.Error(
                        throwable.message ?: "Could not start speech recognition.",
                    ),
                )
            }
    }

    private fun listenerFor(session: Long, cycle: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrentCycle(session, cycle)) return
            Log.i(TAG, "onReadyForSpeech session=$session cycle=$cycle")
            cancelStartupTimeout()
            if (phase == Phase.STARTING && buttonHeld && !releaseRequested) {
                phase = Phase.LISTENING
                dispatch(SpeechEvent.Listening)
            }
        }

        override fun onBeginningOfSpeech() {
            if (!isCurrentCycle(session, cycle)) return
            Log.i(TAG, "onBeginningOfSpeech session=$session cycle=$cycle")
            cancelStartupTimeout()
            if (phase == Phase.STARTING && buttonHeld && !releaseRequested) {
                phase = Phase.LISTENING
                dispatch(SpeechEvent.Listening)
            }
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrentCycle(session, cycle)) return
            Log.i(
                TAG,
                "onEndOfSpeech session=$session cycle=$cycle held=$buttonHeld",
            )
            cancelStartupTimeout()
            phase = Phase.AWAITING_RESULT
            scheduleResultTimeout(session, cycle)
        }

        override fun onError(error: Int) {
            if (!isCurrentCycle(session, cycle)) return
            Log.w(
                TAG,
                "onError session=$session cycle=$cycle code=$error held=$buttonHeld",
            )

            if (releaseRequested || !buttonHeld) {
                finishReleasedSession(
                    session = session,
                    segment = currentCyclePartial,
                    emptyMessage = errorMessage(error),
                )
                return
            }

            if (error.isRecoverableEndpointError()) {
                completeCycleAndContinue(
                    session = session,
                    cycle = cycle,
                    segment = currentCyclePartial,
                )
            } else {
                finishWith(session, SpeechEvent.Error(errorMessage(error)))
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentCycle(session, cycle)) return
            val text = bestResult(results)
            Log.i(
                TAG,
                "onResults session=$session cycle=$cycle hasText=${!text.isNullOrBlank()} " +
                    "held=$buttonHeld",
            )
            completeCycleAndContinue(
                session = session,
                cycle = cycle,
                segment = text?.takeIf(String::isNotBlank) ?: currentCyclePartial,
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrentCycle(session, cycle)) return
            cancelStartupTimeout()
            if (phase == Phase.STARTING && buttonHeld && !releaseRequested) {
                phase = Phase.LISTENING
            }

            val partial = bestResult(partialResults)?.takeIf(String::isNotBlank) ?: return
            currentCyclePartial = partial
            transcript.preview(partial)
                .takeIf(String::isNotBlank)
                ?.let { dispatch(SpeechEvent.PartialResult(it)) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Commits one Android request, then either restarts or finalizes after release. */
    private fun completeCycleAndContinue(session: Long, cycle: Long, segment: String?) {
        if (!isCurrentCycle(session, cycle)) return

        val hadText = joinSpeechSegments(null, segment).isNotEmpty()
        val combinedText = transcript.commit(segment)
        releaseCurrentCycle()

        if (!isCurrentSession(session)) return
        if (releaseRequested || !buttonHeld) {
            finishReleasedSession(
                session = session,
                emptyMessage = "Speech was not recognized. Please try again.",
            )
            return
        }

        // A segment result is deliberately partial at the logical-session level:
        // the user is still holding the button and can continue after a pause.
        if (combinedText.isNotBlank()) {
            dispatch(SpeechEvent.PartialResult(combinedText))
        }
        scheduleNextCycle(session, hadText)
    }

    private fun scheduleNextCycle(session: Long, previousCycleHadText: Boolean) {
        if (!isCurrentSession(session) || !buttonHeld || releaseRequested) return

        val delayMillis = if (previousCycleHadText) {
            consecutiveEmptyCycles = 0
            SUCCESSFUL_CYCLE_RESTART_DELAY_MS
        } else {
            consecutiveEmptyCycles = (consecutiveEmptyCycles + 1)
                .coerceAtMost(MAX_EMPTY_RESTART_EXPONENT + 1)
            val exponent = (consecutiveEmptyCycles - 1).coerceAtMost(MAX_EMPTY_RESTART_EXPONENT)
            (EMPTY_CYCLE_RESTART_DELAY_MS shl exponent)
                .coerceAtMost(MAX_EMPTY_CYCLE_RESTART_DELAY_MS)
        }

        phase = Phase.BETWEEN_CYCLES
        pendingRestart = Runnable {
            pendingRestart = null
            if (!isCurrentSession(session) || !buttonHeld || releaseRequested) return@Runnable
            startRecognizerCycle(session)
        }.also { mainHandler.postDelayed(it, delayMillis) }

        Log.i(
            TAG,
            "Scheduled next recognition cycle session=$session delayMs=$delayMillis " +
                "hadText=$previousCycleHadText",
        )
    }

    private fun finishReleasedSession(
        session: Long,
        segment: String? = null,
        emptyMessage: String,
    ) {
        if (!isCurrentSession(session)) return
        transcript.commit(segment)
        val finalText = transcript.text
        finishWith(
            session,
            if (finalText.isBlank()) {
                SpeechEvent.Error(emptyMessage)
            } else {
                SpeechEvent.FinalResult(finalText)
            },
        )
    }

    private fun scheduleStartupTimeout(session: Long, cycle: Long) {
        cancelStartupTimeout()
        startupTimeout = Runnable {
            if (!isCurrentCycle(session, cycle) || phase != Phase.STARTING) return@Runnable

            Log.w(TAG, "Recognition service did not start session=$session cycle=$cycle")
            if (releaseRequested || !buttonHeld) {
                finishReleasedSession(
                    session = session,
                    segment = currentCyclePartial,
                    emptyMessage = "Speech recognition did not start within 5 seconds.",
                )
            } else {
                completeCycleAndContinue(session, cycle, currentCyclePartial)
            }
        }.also { mainHandler.postDelayed(it, STARTUP_TIMEOUT_MS) }
    }

    private fun scheduleResultTimeout(session: Long, cycle: Long) {
        cancelResultTimeout()
        resultTimeout = Runnable {
            if (!isCurrentCycle(session, cycle)) return@Runnable
            if (phase != Phase.AWAITING_RESULT && phase != Phase.STOPPING) return@Runnable

            Log.w(TAG, "Recognition service did not finish session=$session cycle=$cycle")
            if (releaseRequested || !buttonHeld) {
                finishReleasedSession(
                    session = session,
                    segment = currentCyclePartial,
                    emptyMessage =
                        "Speech recognition did not return a result within 8 seconds.",
                )
            } else {
                completeCycleAndContinue(session, cycle, currentCyclePartial)
            }
        }.also { mainHandler.postDelayed(it, FINAL_RESULT_TIMEOUT_MS) }
    }

    private fun finishWith(session: Long, event: SpeechEvent) {
        if (!isCurrentSession(session)) return
        invalidateSession(session)
        dispatch(event)
    }

    private fun releaseActiveSession() {
        val session = activeSession
        if (session != NO_SESSION) invalidateSession(session)
    }

    private fun invalidateSession(session: Long) {
        if (activeSession != session) return

        activeSession = NO_SESSION
        activeLanguageTag = ""
        buttonHeld = false
        releaseRequested = false
        consecutiveEmptyCycles = 0
        cancelPendingRestart()
        releaseCurrentCycle()
        transcript.clear()
        phase = Phase.IDLE
    }

    /** Invalidates callback identity before cancel/destroy can produce callbacks. */
    private fun releaseCurrentCycle() {
        activeCycle = NO_CYCLE
        cancelStartupTimeout()
        cancelResultTimeout()

        val staleRecognizer = recognizer
        recognizer = null
        currentCyclePartial = ""
        if (activeSession != NO_SESSION) phase = Phase.BETWEEN_CYCLES
        staleRecognizer?.let(::releaseRecognizer)
    }

    private fun clearFailedCycle(cycle: Long) {
        if (activeCycle != cycle) return
        activeCycle = NO_CYCLE
        recognizer = null
        currentCyclePartial = ""
        cancelStartupTimeout()
        cancelResultTimeout()
        phase = Phase.BETWEEN_CYCLES
    }

    private fun releaseRecognizer(staleRecognizer: SpeechRecognizer) {
        runCatching { staleRecognizer.cancel() }
            .onFailure { Log.w(TAG, "cancel failed while releasing recognizer", it) }
        runCatching { staleRecognizer.destroy() }
            .onFailure { Log.w(TAG, "destroy failed while releasing recognizer", it) }
    }

    private fun cancelStartupTimeout() {
        startupTimeout?.let(mainHandler::removeCallbacks)
        startupTimeout = null
    }

    private fun cancelResultTimeout() {
        resultTimeout?.let(mainHandler::removeCallbacks)
        resultTimeout = null
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let(mainHandler::removeCallbacks)
        pendingRestart = null
    }

    private fun isCurrentSession(session: Long): Boolean =
        !destroyed && activeSession == session

    private fun isCurrentCycle(session: Long, cycle: Long): Boolean =
        isCurrentSession(session) && activeCycle == cycle

    private fun bestResult(bundle: Bundle?): String? = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.let { joinSpeechSegments(null, it) }

    private fun Int.isRecoverableEndpointError(): Boolean = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        -> true

        else -> false
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "The recognition session was interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is not allowed."
        SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The recognition service timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Speech was not recognized. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognition service is busy."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many recognition requests."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The recognition service disconnected."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The selected language is not supported."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The language pack is currently unavailable."
        else -> "Unknown speech recognition error ($error)."
    }

    @Suppress("DEPRECATION")
    private fun findPreferredRecognitionService(): ComponentName? {
        val systemFlags =
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

        val candidates = appContext.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                val applicationInfo = serviceInfo.applicationInfo ?: return@mapNotNull null

                SpeechServiceCandidate(
                    packageName = serviceInfo.packageName,
                    className = expandClassName(serviceInfo.packageName, serviceInfo.name),
                    permission = serviceInfo.permission,
                    exported = serviceInfo.exported,
                    serviceEnabled = serviceInfo.enabled,
                    applicationEnabled = applicationInfo.enabled,
                    systemApp = (applicationInfo.flags and systemFlags) != 0,
                )
            }

        return RecognitionServicePolicy.select(candidates)?.let { selected ->
            ComponentName(selected.packageName, selected.className)
        }
    }

    private fun expandClassName(packageName: String, name: String): String = when {
        name.startsWith(".") -> packageName + name
        "." !in name -> "$packageName.$name"
        else -> name
    }

    private fun dispatch(event: SpeechEvent) {
        if (!destroyed) eventListener?.invoke(event)
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private enum class Phase {
        IDLE,
        STARTING,
        LISTENING,
        AWAITING_RESULT,
        BETWEEN_CYCLES,
        STOPPING,
    }

    private companion object {
        const val TAG = "VoiceBridgeSpeech"
        const val NO_SESSION = -1L
        const val NO_CYCLE = -1L
        const val STARTUP_TIMEOUT_MS = 5_000L
        const val FINAL_RESULT_TIMEOUT_MS = 8_000L
        const val SUCCESSFUL_CYCLE_RESTART_DELAY_MS = 180L
        const val EMPTY_CYCLE_RESTART_DELAY_MS = 300L
        const val MAX_EMPTY_CYCLE_RESTART_DELAY_MS = 2_400L
        const val MAX_EMPTY_RESTART_EXPONENT = 3
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }
}
