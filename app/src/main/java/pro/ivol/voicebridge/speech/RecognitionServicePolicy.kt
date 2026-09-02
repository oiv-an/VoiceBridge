package pro.ivol.voicebridge.speech

internal data class SpeechServiceCandidate(
    val packageName: String,
    val className: String,
    val permission: String?,
    val exported: Boolean,
    val serviceEnabled: Boolean,
    val applicationEnabled: Boolean,
    val systemApp: Boolean,
)

internal object RecognitionServicePolicy {
    private const val BIND_PERMISSION =
        "android.permission.BIND_SPEECH_RECOGNITION"

    private val googleRank = mapOf(
        "com.google.android.googlequicksearchbox" to 0,
        "com.google.android.tts" to 1,
        "com.google.android.as" to 2,
    )

    fun select(candidates: List<SpeechServiceCandidate>): SpeechServiceCandidate? =
        candidates
            .asSequence()
            .filter(::isUsable)
            .minWithOrNull(
                compareBy<SpeechServiceCandidate>(
                    { googleRank[it.packageName] ?: 100 },
                    { if (it.systemApp) 0 else 1 },
                    SpeechServiceCandidate::packageName,
                    SpeechServiceCandidate::className,
                ),
            )

    private fun isUsable(candidate: SpeechServiceCandidate): Boolean {
        val isFake = candidate.className
            .substringAfterLast('.')
            .equals("FakeRecognitionService", ignoreCase = true)
        val isKnownGoogleService = candidate.packageName in googleRank
        val isTrusted = candidate.systemApp || isKnownGoogleService
        // Current Google app builds expose a working RecognitionService without
        // declaring BIND_SPEECH_RECOGNITION on the service itself. Accept that
        // narrow, package-ranked exception; vendor fallbacks still need it.
        val hasExpectedBinding =
            candidate.permission == BIND_PERMISSION || isKnownGoogleService

        return !isFake &&
            isTrusted &&
            hasExpectedBinding &&
            candidate.exported &&
            candidate.serviceEnabled &&
            candidate.applicationEnabled
    }
}
