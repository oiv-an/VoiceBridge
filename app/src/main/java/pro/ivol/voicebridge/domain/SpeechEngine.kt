package pro.ivol.voicebridge.domain

interface SpeechEngine {
    val displayName: String

    fun setListener(listener: (SpeechEvent) -> Unit)

    fun startListening(languageTag: String)

    fun stopListening()

    fun cancelListening()

    fun destroy()
}

interface PreparableSpeechEngine {
    val isPrepared: Boolean

    fun prepare()
}

sealed interface SpeechEvent {
    data class ModeChanged(
        val mode: SpeechRecognitionMode,
        val backendName: String,
    ) : SpeechEvent

    data class ModeChangeFailed(
        val message: String,
        val fallbackMode: SpeechRecognitionMode,
        val fallbackBackendName: String,
    ) : SpeechEvent

    data object Preparing : SpeechEvent

    data object Ready : SpeechEvent

    data object Listening : SpeechEvent

    data object Processing : SpeechEvent

    data class PartialResult(val text: String) : SpeechEvent

    data class FinalResult(val text: String) : SpeechEvent

    data object Stopped : SpeechEvent

    data class Error(val message: String) : SpeechEvent

    data class Unresponsive(val message: String) : SpeechEvent
}
