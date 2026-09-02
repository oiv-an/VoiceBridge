package pro.ivol.voicebridge.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import pro.ivol.voicebridge.domain.PreparableSpeechEngine
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent
import pro.ivol.voicebridge.domain.SpeechModeController
import pro.ivol.voicebridge.domain.SpeechRecognitionMode

/**
 * Keeps the proven Google recognizer alive while Nemotron is warming up.
 * The local mode is activated and persisted only after the native model reports Ready.
 */
class SwitchableSpeechEngine(context: Context) : SpeechEngine, SpeechModeController {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false

    @Volatile
    private var eventListener: ((SpeechEvent) -> Unit)? = null

    @Volatile
    private var activeEngine: SpeechEngine = createEngine(SpeechRecognitionMode.ONLINE)

    private var nextGeneration = 0L
    private var activeGeneration = 0L
    private var pendingLocal: PendingLocal? = null
    private var pendingPreparationTimeout: Runnable? = null
    private var restoreLocalWhenListenerAttached = false
    private var localDisabledForProcess = false

    @Volatile
    override var selectedMode: SpeechRecognitionMode = SpeechRecognitionMode.ONLINE
        private set

    override val localModelInstalled: Boolean
        get() =
            NemotronModelSpec.isInstalled(appContext) &&
                VietnameseModelSpec.isInstalled(appContext)

    override val localModeAvailable: Boolean
        get() = localModelInstalled && !localDisabledForProcess

    override val displayName: String
        get() = activeEngine.displayName

    init {
        bindAsActive(activeEngine)

        val storedMode = runCatching {
            SpeechRecognitionMode.valueOf(
                preferences.getString(KEY_SELECTED_MODE, null).orEmpty(),
            )
        }.getOrDefault(SpeechRecognitionMode.ONLINE)

        restoreLocalWhenListenerAttached =
            storedMode == SpeechRecognitionMode.NVIDIA_LOCAL && localModeAvailable
        if (storedMode == SpeechRecognitionMode.NVIDIA_LOCAL && !localModeAvailable) {
            persistSelectedMode(SpeechRecognitionMode.ONLINE)
        }
    }

    @Synchronized
    override fun setListener(listener: (SpeechEvent) -> Unit) {
        if (destroyed) return
        eventListener = listener

        if (restoreLocalWhenListenerAttached) {
            restoreLocalWhenListenerAttached = false
            selectMode(SpeechRecognitionMode.NVIDIA_LOCAL)
        }
    }

    @Synchronized
    override fun selectMode(mode: SpeechRecognitionMode): Result<Unit> {
        if (destroyed) {
            return Result.failure(IllegalStateException("The speech recognizer has been stopped."))
        }

        return when (mode) {
            SpeechRecognitionMode.ONLINE -> selectOnlineMode()
            SpeechRecognitionMode.NVIDIA_LOCAL -> selectLocalMode()
        }
    }

    override fun startListening(languageTag: String) {
        if (!destroyed) activeEngine.startListening(languageTag)
    }

    override fun stopListening() {
        if (!destroyed) activeEngine.stopListening()
    }

    override fun cancelListening() {
        if (!destroyed) activeEngine.cancelListening()
    }

    @Synchronized
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        eventListener = null
        cancelPendingPreparationTimeout()
        pendingLocal?.engine?.destroy()
        pendingLocal = null
        activeEngine.destroy()
    }

    private fun selectOnlineMode(): Result<Unit> {
        restoreLocalWhenListenerAttached = false
        cancelPendingPreparationTimeout()
        pendingLocal?.engine?.destroy()
        pendingLocal = null

        if (selectedMode == SpeechRecognitionMode.ONLINE) {
            persistSelectedMode(SpeechRecognitionMode.ONLINE)
            emitModeChanged()
            return Result.success(Unit)
        }

        val replacement = runCatching {
            createEngine(SpeechRecognitionMode.ONLINE)
        }.getOrElse { throwable ->
            return Result.failure(throwable)
        }

        val previous = activeEngine
        activeEngine = replacement
        bindAsActive(replacement)
        selectedMode = SpeechRecognitionMode.ONLINE
        persistSelectedMode(selectedMode)
        previous.destroy()
        emitModeChanged()
        return Result.success(Unit)
    }

    private fun selectLocalMode(): Result<Unit> {
        if (!localModeAvailable) {
            return Result.failure(
                IllegalStateException(
                    if (localModelInstalled) {
                        "Local recognition is temporarily disabled. Restart the app."
                    } else {
                        "The local recognition models are not installed yet."
                    },
                ),
            )
        }
        if (selectedMode == SpeechRecognitionMode.NVIDIA_LOCAL || pendingLocal != null) {
            return Result.success(Unit)
        }

        val candidate = runCatching {
            createEngine(SpeechRecognitionMode.NVIDIA_LOCAL)
        }.getOrElse { throwable ->
            return Result.failure(throwable)
        }
        val generation = ++nextGeneration
        pendingLocal = PendingLocal(candidate, generation)
        candidate.setListener { event ->
            onPendingLocalEvent(candidate, generation, event)
        }
        val timeout = Runnable {
            onPendingPreparationTimedOut(candidate, generation)
        }
        pendingPreparationTimeout = timeout
        mainHandler.postDelayed(timeout, PREPARATION_TIMEOUT_MS)
        (candidate as PreparableSpeechEngine).prepare()
        return Result.success(Unit)
    }

    @Synchronized
    private fun onPendingLocalEvent(
        candidate: SpeechEngine,
        generation: Long,
        event: SpeechEvent,
    ) {
        if (destroyed) return
        val pending = pendingLocal
        if (pending?.engine !== candidate || pending.generation != generation) return

        when (event) {
            SpeechEvent.Preparing -> emit(event)

            SpeechEvent.Ready -> {
                cancelPendingPreparationTimeout()
                pendingLocal = null
                val previous = activeEngine
                activeEngine = candidate
                bindAsActive(candidate)
                selectedMode = SpeechRecognitionMode.NVIDIA_LOCAL
                persistSelectedMode(selectedMode)
                previous.destroy()
                emitModeChanged()
                emit(SpeechEvent.Ready)
            }

            is SpeechEvent.Error -> {
                cancelPendingPreparationTimeout()
                pendingLocal = null
                candidate.destroy()
                selectedMode = SpeechRecognitionMode.ONLINE
                persistSelectedMode(selectedMode)
                emit(
                    SpeechEvent.ModeChangeFailed(
                        message = event.message,
                        fallbackMode = selectedMode,
                        fallbackBackendName = activeEngine.displayName,
                    ),
                )
            }

            else -> Unit
        }
    }

    @Synchronized
    private fun onPendingPreparationTimedOut(
        candidate: SpeechEngine,
        generation: Long,
    ) {
        if (destroyed) return
        val pending = pendingLocal
        if (pending?.engine !== candidate || pending.generation != generation) return

        pendingPreparationTimeout = null
        pendingLocal = null
        localDisabledForProcess = true
        candidate.destroy()
        selectedMode = SpeechRecognitionMode.ONLINE
        persistSelectedMode(selectedMode)
        emit(
            SpeechEvent.ModeChangeFailed(
                message = "Local recognition did not load within one minute and is " +
                    "temporarily disabled. " +
                    "Google remains available.",
                fallbackMode = selectedMode,
                fallbackBackendName = activeEngine.displayName,
            ),
        )
    }

    private fun bindAsActive(engine: SpeechEngine) {
        val generation = ++nextGeneration
        activeGeneration = generation
        engine.setListener { event ->
            onActiveEvent(engine, generation, event)
        }
    }

    @Synchronized
    private fun onActiveEvent(
        engine: SpeechEngine,
        generation: Long,
        event: SpeechEvent,
    ) {
        if (
            destroyed ||
            activeEngine !== engine ||
            activeGeneration != generation
        ) {
            return
        }
        if (
            selectedMode == SpeechRecognitionMode.NVIDIA_LOCAL &&
            event is SpeechEvent.Unresponsive
        ) {
            fallbackToOnlineAfterHang(event.message)
        } else {
            emit(event)
        }
    }

    private fun fallbackToOnlineAfterHang(message: String) {
        localDisabledForProcess = true
        val replacement = runCatching {
            createEngine(SpeechRecognitionMode.ONLINE)
        }.getOrElse {
            emit(SpeechEvent.Error(message))
            return
        }

        val previous = activeEngine
        activeEngine = replacement
        bindAsActive(replacement)
        selectedMode = SpeechRecognitionMode.ONLINE
        persistSelectedMode(selectedMode)
        previous.destroy()
        emit(
            SpeechEvent.ModeChangeFailed(
                message = "$message Local recognition is disabled until the app is restarted.",
                fallbackMode = selectedMode,
                fallbackBackendName = activeEngine.displayName,
            ),
        )
    }

    private fun cancelPendingPreparationTimeout() {
        pendingPreparationTimeout?.let(mainHandler::removeCallbacks)
        pendingPreparationTimeout = null
    }

    private fun emitModeChanged() {
        emit(
            SpeechEvent.ModeChanged(
                mode = selectedMode,
                backendName = activeEngine.displayName,
            ),
        )
    }

    private fun emit(event: SpeechEvent) {
        eventListener?.invoke(event)
    }

    private fun createEngine(mode: SpeechRecognitionMode): SpeechEngine = when (mode) {
        SpeechRecognitionMode.ONLINE -> AndroidSpeechRecognizerEngine(appContext)
        SpeechRecognitionMode.NVIDIA_LOCAL -> {
            require(localModeAvailable) { "The local recognition models are not installed." }
            LanguageRoutingLocalSpeechEngine(appContext)
        }
    }

    private fun persistSelectedMode(mode: SpeechRecognitionMode) {
        preferences.edit { putString(KEY_SELECTED_MODE, mode.name) }
    }

    private data class PendingLocal(
        val engine: SpeechEngine,
        val generation: Long,
    )

    private companion object {
        const val PREFERENCES_NAME = "voicebridge_speech"
        const val KEY_SELECTED_MODE = "selected_mode"
        const val PREPARATION_TIMEOUT_MS = 60_000L
    }
}
