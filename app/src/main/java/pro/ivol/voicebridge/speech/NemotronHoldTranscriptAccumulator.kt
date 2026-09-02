package pro.ivol.voicebridge.speech

/**
 * Joins the independent Nemotron utterances produced during one physical
 * push-to-talk hold. Segment ids make committing idempotent without dropping
 * a deliberately repeated phrase in the next segment.
 */
internal class NemotronHoldTranscriptAccumulator {
    private val committed = StringBuilder()
    private var lastCommittedSegmentId = -1L

    val committedText: String
        get() = committed.toString()

    fun commit(segmentId: Long, rawText: String): Boolean {
        require(segmentId >= 0L) { "Segment id must not be negative." }
        if (segmentId <= lastCommittedSegmentId) return false

        lastCommittedSegmentId = segmentId
        val text = normalize(rawText)
        if (text.isEmpty()) return false

        if (committed.isNotEmpty()) committed.append(' ')
        committed.append(text)
        return true
    }

    fun preview(segmentId: Long, rawText: String): String {
        require(segmentId >= 0L) { "Segment id must not be negative." }
        if (segmentId <= lastCommittedSegmentId) return committedText

        val text = normalize(rawText)
        return when {
            text.isEmpty() -> committedText
            committed.isEmpty() -> text
            else -> "$committed $text"
        }
    }

    private fun normalize(text: String): String =
        WHITESPACE.replace(text.trim(), " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
