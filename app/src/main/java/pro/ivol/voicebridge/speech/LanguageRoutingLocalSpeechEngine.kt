package pro.ivol.voicebridge.speech

import android.content.Context
import android.util.Log
import pro.ivol.voicebridge.domain.PreparableSpeechEngine
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent

/**
 * Routes Vietnamese to its dedicated local model and all other supported
 * languages to the multilingual NVIDIA model.
 */
class LanguageRoutingLocalSpeechEngine(
    context: Context,
    private val nemotronEngine: NemotronSpeechEngine = NemotronSpeechEngine(context),
    private val vietnameseEngine: VietnameseSpeechEngine = VietnameseSpeechEngine(context),
) : SpeechEngine, PreparableSpeechEngine {
    override val displayName = "Local (NVIDIA + Vietnamese)"

    private val stateLock = Any()

    @Volatile
    private var eventListener: ((SpeechEvent) -> Unit)? = null

    @Volatile
    private var destroyed = false

    private var preparationInProgress = false
    private var nemotronReady = nemotronEngine.isPrepared
    private var vietnameseReady = vietnameseEngine.isPrepared
    private var nextSessionGeneration = 0L
    private var activeSession: ActiveSession? = null
    private val awaitingCancellationStops = mutableSetOf<Child>()

    override val isPrepared: Boolean
        get() =
            !destroyed &&
                nemotronEngine.isPrepared &&
                vietnameseEngine.isPrepared

    init {
        nemotronEngine.setListener { event ->
            onChildEvent(Child.NEMOTRON, event)
        }
        vietnameseEngine.setListener { event ->
            onChildEvent(Child.VIETNAMESE, event)
        }
    }

    override fun setListener(listener: (SpeechEvent) -> Unit) {
        synchronized(stateLock) {
            if (!destroyed) eventListener = listener
        }
    }

    override fun prepare() {
        val enginesToPrepare: List<PreparableSpeechEngine>
        val alreadyReady: Boolean

        synchronized(stateLock) {
            if (destroyed) return

            nemotronReady = nemotronEngine.isPrepared
            vietnameseReady = vietnameseEngine.isPrepared
            alreadyReady = nemotronReady && vietnameseReady
            if (alreadyReady) {
                preparationInProgress = false
                enginesToPrepare = emptyList()
            } else {
                if (preparationInProgress) return
                preparationInProgress = true
                enginesToPrepare = buildList {
                    if (!nemotronReady) add(nemotronEngine)
                    if (!vietnameseReady) add(vietnameseEngine)
                }
            }
        }

        if (alreadyReady) {
            emit(SpeechEvent.Ready)
            return
        }

        emit(SpeechEvent.Preparing)
        enginesToPrepare.forEach { engine ->
            try {
                engine.prepare()
            } catch (throwable: Throwable) {
                onPreparationException(throwable)
            }
        }
    }

    override fun startListening(languageTag: String) {
        if (!isPrepared) {
            prepare()
            return
        }

        val child = childForLanguage(languageTag)
        val session = synchronized(stateLock) {
            if (
                destroyed ||
                activeSession != null ||
                child in awaitingCancellationStops
            ) {
                return
            }
            ActiveSession(
                generation = ++nextSessionGeneration,
                child = child,
            ).also { activeSession = it }
        }

        try {
            engineFor(child).startListening(languageTag)
        } catch (throwable: Throwable) {
            val shouldReport = synchronized(stateLock) {
                if (activeSession == session) {
                    activeSession = null
                    true
                } else {
                    false
                }
            }
            if (shouldReport) {
                emit(
                    SpeechEvent.Error(
                        throwable.message ?: "Could not start local speech recognition.",
                    ),
                )
            }
        }
    }

    override fun stopListening() {
        val session = synchronized(stateLock) {
            if (destroyed) return
            activeSession
        }
        if (session == null) {
            emit(SpeechEvent.Stopped)
            return
        }

        try {
            engineFor(session.child).stopListening()
        } catch (throwable: Throwable) {
            val shouldReport = synchronized(stateLock) {
                if (activeSession == session) {
                    activeSession = null
                    true
                } else {
                    false
                }
            }
            if (shouldReport) {
                emit(
                    SpeechEvent.Error(
                        throwable.message ?: "Could not stop local speech recognition.",
                    ),
                )
            }
        }
    }

    override fun cancelListening() {
        val session = synchronized(stateLock) {
            if (destroyed) return
            nextSessionGeneration += 1
            activeSession.also { current ->
                activeSession = null
                current?.let { awaitingCancellationStops += it.child }
            }
        }

        if (session != null) {
            runCatching { engineFor(session.child).cancelListening() }
                .onFailure { throwable ->
                    synchronized(stateLock) {
                        awaitingCancellationStops -= session.child
                    }
                    Log.w(TAG, "Could not cancel the active local recognizer", throwable)
                }
        }
        emit(SpeechEvent.Stopped)
    }

    override fun destroy() {
        synchronized(stateLock) {
            if (destroyed) return
            destroyed = true
            preparationInProgress = false
            nextSessionGeneration += 1
            activeSession = null
            awaitingCancellationStops.clear()
            eventListener = null
        }

        runCatching { nemotronEngine.destroy() }
            .onFailure { throwable ->
                Log.w(TAG, "Could not destroy the NVIDIA recognizer", throwable)
            }
        runCatching { vietnameseEngine.destroy() }
            .onFailure { throwable ->
                Log.w(TAG, "Could not destroy the Vietnamese recognizer", throwable)
            }
    }

    private fun onChildEvent(child: Child, event: SpeechEvent) {
        val forwardedEvent = synchronized(stateLock) {
            if (destroyed) return

            if (child in awaitingCancellationStops) {
                if (event.isTerminalSessionEvent()) {
                    awaitingCancellationStops -= child
                }
                return@synchronized null
            }

            when (event) {
                SpeechEvent.Preparing -> null
                SpeechEvent.Ready -> onChildReady(child)
                is SpeechEvent.Error -> {
                    val session = activeSession
                    when {
                        session?.child == child -> {
                            activeSession = null
                            event
                        }

                        preparationInProgress -> {
                            preparationInProgress = false
                            event
                        }

                        else -> null
                    }
                }

                is SpeechEvent.Unresponsive -> {
                    val session = activeSession
                    when {
                        session?.child == child -> {
                            activeSession = null
                            event
                        }

                        preparationInProgress -> {
                            preparationInProgress = false
                            event
                        }

                        else -> null
                    }
                }

                else -> {
                    val session = activeSession
                    if (session?.child != child) {
                        null
                    } else {
                        if (event.isTerminalSessionEvent()) activeSession = null
                        event
                    }
                }
            }
        }

        forwardedEvent?.let(::emit)
    }

    /** Must be called while holding [stateLock]. */
    private fun onChildReady(child: Child): SpeechEvent? {
        if (!preparationInProgress) return null

        when (child) {
            Child.NEMOTRON -> nemotronReady = true
            Child.VIETNAMESE -> vietnameseReady = true
        }
        return if (nemotronReady && vietnameseReady) {
            preparationInProgress = false
            SpeechEvent.Ready
        } else {
            null
        }
    }

    private fun onPreparationException(throwable: Throwable) {
        val shouldReport = synchronized(stateLock) {
            if (destroyed || !preparationInProgress) {
                false
            } else {
                preparationInProgress = false
                true
            }
        }
        if (shouldReport) {
            emit(
                SpeechEvent.Error(
                    throwable.message ?: "Could not prepare local speech recognition.",
                ),
            )
        }
    }

    private fun childForLanguage(languageTag: String): Child {
        val normalized = languageTag.trim().replace('_', '-').lowercase()
        return if (normalized == VIETNAMESE_LANGUAGE || normalized.startsWith("vi-")) {
            Child.VIETNAMESE
        } else {
            Child.NEMOTRON
        }
    }

    private fun engineFor(child: Child): SpeechEngine = when (child) {
        Child.NEMOTRON -> nemotronEngine
        Child.VIETNAMESE -> vietnameseEngine
    }

    private fun SpeechEvent.isTerminalSessionEvent(): Boolean = when (this) {
        is SpeechEvent.FinalResult,
        is SpeechEvent.Error,
        is SpeechEvent.Unresponsive,
        SpeechEvent.Stopped,
        -> true

        else -> false
    }

    private fun emit(event: SpeechEvent) {
        val listener = synchronized(stateLock) {
            if (destroyed) null else eventListener
        }
        listener?.invoke(event)
    }

    private enum class Child {
        NEMOTRON,
        VIETNAMESE,
    }

    private data class ActiveSession(
        val generation: Long,
        val child: Child,
    )

    private companion object {
        const val VIETNAMESE_LANGUAGE = "vi"
        const val TAG = "VoiceBridgeLocalRouter"
    }
}
