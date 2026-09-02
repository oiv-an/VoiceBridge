package pro.ivol.voicebridge.domain

/**
 * Speaks short pieces of conversation text.
 *
 * Implementations may initialize asynchronously. [speak] returns immediately and
 * its returned ID is repeated in every event for that utterance, which lets a
 * ViewModel distinguish between multiple speak buttons without retaining Android
 * framework objects.
 */
interface TextSpeaker : AutoCloseable {
    fun setListener(listener: (TextSpeakerEvent) -> Unit)

    /**
     * Requests speech and returns the utterance ID used by subsequent events.
     *
     * A request superseded before asynchronous initialization completes may be
     * dropped without an event; the most recent request is retained.
     */
    fun speak(text: String, language: AppLanguage): String

    /** Stops active speech and discards a request still waiting for initialization. */
    fun stop()

    override fun close()
}

sealed interface TextSpeakerEvent {
    data class Started(val utteranceId: String) : TextSpeakerEvent

    data class Done(val utteranceId: String) : TextSpeakerEvent

    data class Error(
        val utteranceId: String,
        val message: String,
    ) : TextSpeakerEvent
}
