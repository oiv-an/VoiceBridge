package pro.ivol.voicebridge.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Groups contiguous digit tokens of at least 1,000 without changing stored text. */
internal object GroupedLargeNumberVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val insertSeparatorBefore = BooleanArray(source.length)

        DIGIT_TOKEN.findAll(source).forEach { match ->
            val digits = match.value
            if (digits.trimStart('0').length < MIN_GROUPED_DIGITS) return@forEach

            for (position in 1 until digits.length) {
                if ((digits.length - position) % GROUP_SIZE == 0) {
                    insertSeparatorBefore[match.range.first + position] = true
                }
            }
        }

        if (insertSeparatorBefore.none { it }) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val transformed = StringBuilder(source.length + insertSeparatorBefore.count { it })
        val originalToTransformed = IntArray(source.length + 1)
        val transformedToOriginal = mutableListOf(0)

        source.forEachIndexed { index, character ->
            if (insertSeparatorBefore[index]) {
                transformed.append(' ')
                transformedToOriginal += index
            }

            originalToTransformed[index] = transformed.length
            transformed.append(character)
            transformedToOriginal += index + 1
        }
        originalToTransformed[source.length] = transformed.length

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed[offset.coerceIn(0, source.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)]
        }

        return TransformedText(AnnotatedString(transformed.toString()), mapping)
    }

    private const val MIN_GROUPED_DIGITS = 4
    private const val GROUP_SIZE = 3
    private val DIGIT_TOKEN = Regex("\\d+")
}
