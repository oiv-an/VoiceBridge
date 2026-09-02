package pro.ivol.voicebridge.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSegmentAccumulatorTest {
    @Test
    fun `joins Russian and Vietnamese segments with one boundary space`() {
        assertEquals(
            "Привет xin chào bạn",
            joinSpeechSegments("Привет  ", "  xin chào bạn"),
        )
    }

    @Test
    fun `trims Unicode space separators at segment boundaries`() {
        assertEquals(
            "Привет xin chào",
            joinSpeechSegments("\u00A0Привет\u2007", "\u202Fxin chào\u3000"),
        )
    }

    @Test
    fun `ignores blank segments`() {
        assertEquals("xin chào", joinSpeechSegments("\u2007", " xin chào "))
        assertEquals("Привет", joinSpeechSegments(" Привет ", "\u00A0"))
    }

    @Test
    fun `preview does not commit current cycle hypothesis`() {
        val accumulator = SpeechSegmentAccumulator()

        accumulator.commit(" первая фраза ")

        assertEquals("первая фраза xin chào", accumulator.preview(" xin chào "))
        assertEquals("первая фраза", accumulator.text)
    }

    @Test
    fun `commits consecutive cycle results`() {
        val accumulator = SpeechSegmentAccumulator()

        assertEquals("первая", accumulator.commit(" первая "))
        assertEquals("первая вторая", accumulator.commit(" вторая "))
        assertEquals("первая вторая", accumulator.commit("  "))

        accumulator.clear()
        assertEquals("", accumulator.text)
    }
}
