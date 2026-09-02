package pro.ivol.voicebridge.speech

/**
 * Builds the transcript produced by consecutive Android speech-recognition cycles.
 *
 * Google may end an individual recognition cycle after a pause even though the user
 * is still holding push-to-talk. The committed text survives those cycle boundaries;
 * [preview] adds the current cycle's partial hypothesis without committing it.
 */
internal class SpeechSegmentAccumulator {
    private var committedText: String = ""

    val text: String
        get() = committedText

    fun clear() {
        committedText = ""
    }

    fun preview(segment: String?): String = joinSpeechSegments(committedText, segment)

    fun commit(segment: String?): String {
        committedText = joinSpeechSegments(committedText, segment)
        return committedText
    }
}

/** Trims Unicode space characters and inserts exactly one space at a cycle boundary. */
internal fun joinSpeechSegments(first: String?, second: String?): String =
    listOfNotNull(first.unicodeTrimmedOrNull(), second.unicodeTrimmedOrNull())
        .joinToString(separator = " ")

private fun String?.unicodeTrimmedOrNull(): String? = this
    ?.trim { character ->
        Character.isWhitespace(character) || Character.isSpaceChar(character)
    }
    ?.takeIf(String::isNotEmpty)
