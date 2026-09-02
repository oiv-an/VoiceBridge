package pro.ivol.voicebridge.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedLargeNumberVisualTransformationTest {
    @Test
    fun `groups every contiguous number from one thousand without changing phrase`() {
        val transformed = GroupedLargeNumberVisualTransformation.filter(
            AnnotatedString("Цена 1000, потом 15000000 и 999"),
        )

        assertEquals("Цена 1 000, потом 15 000 000 и 999", transformed.text.text)
    }

    @Test
    fun `keeps scale decimals and small or zero padded values unchanged`() {
        val transformed = GroupedLargeNumberVisualTransformation.filter(
            AnnotatedString("1,5 triệu; 0001; 500k"),
        )

        assertEquals("1,5 triệu; 0001; 500k", transformed.text.text)
    }

    @Test
    fun `cursor offsets remain stable around inserted grouping spaces`() {
        val transformed = GroupedLargeNumberVisualTransformation.filter(
            AnnotatedString("15000000"),
        )

        assertEquals("15 000 000", transformed.text.text)
        assertEquals(3, transformed.offsetMapping.originalToTransformed(2))
        assertEquals(7, transformed.offsetMapping.originalToTransformed(5))
        assertEquals(10, transformed.offsetMapping.originalToTransformed(8))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(2))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(3))
        assertEquals(8, transformed.offsetMapping.transformedToOriginal(10))
    }
}
