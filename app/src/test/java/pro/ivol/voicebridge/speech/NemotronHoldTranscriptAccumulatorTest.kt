package pro.ivol.voicebridge.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NemotronHoldTranscriptAccumulatorTest {
    @Test
    fun `committed segments and current partial form one hold transcript`() {
        val transcript = NemotronHoldTranscriptAccumulator()

        assertEquals("первая фраза", transcript.preview(0L, "  первая   фраза "))
        assertTrue(transcript.commit(0L, "первая фраза"))
        assertEquals(
            "первая фраза вторая",
            transcript.preview(1L, " вторая "),
        )
        assertTrue(transcript.commit(1L, "вторая фраза"))

        assertEquals("первая фраза вторая фраза", transcript.committedText)
    }

    @Test
    fun `repeated commit for the same segment is idempotent`() {
        val transcript = NemotronHoldTranscriptAccumulator()

        assertTrue(transcript.commit(4L, "hello"))
        assertFalse(transcript.commit(4L, "hello"))
        assertFalse(transcript.commit(3L, "older result"))

        assertEquals("hello", transcript.committedText)
    }

    @Test
    fun `same words in distinct segments are preserved as intentional speech`() {
        val transcript = NemotronHoldTranscriptAccumulator()

        transcript.commit(0L, "да")
        transcript.commit(1L, "да")

        assertEquals("да да", transcript.committedText)
    }

    @Test
    fun `empty endpoint advances safely without adding separators`() {
        val transcript = NemotronHoldTranscriptAccumulator()

        assertFalse(transcript.commit(0L, "   \n "))
        assertEquals("", transcript.committedText)
        assertEquals("привет", transcript.preview(1L, "привет"))
        assertTrue(transcript.commit(1L, "привет"))

        assertEquals("привет", transcript.committedText)
    }

    @Test
    fun `many endpoint segments remain ordered during a long hold`() {
        val transcript = NemotronHoldTranscriptAccumulator()

        repeat(1_000) { index ->
            assertTrue(transcript.commit(index.toLong(), "word$index"))
        }

        val words = transcript.committedText.split(' ')
        assertEquals(1_000, words.size)
        assertEquals("word0", words.first())
        assertEquals("word999", words.last())
    }
}
