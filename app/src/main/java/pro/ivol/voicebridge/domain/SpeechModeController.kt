package pro.ivol.voicebridge.domain

enum class SpeechRecognitionMode {
    ONLINE,
    NVIDIA_LOCAL,
}

interface SpeechModeController {
    val selectedMode: SpeechRecognitionMode

    val localModelInstalled: Boolean

    val localModeAvailable: Boolean

    fun selectMode(mode: SpeechRecognitionMode): Result<Unit>
}
