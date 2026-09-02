package pro.ivol.voicebridge.screen

data class ScreenTextBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class ScreenTextElement(
    val id: String,
    val source: String,
    val bounds: ScreenTextBounds,
    /** Kept only in memory so translations can follow their scrollable container. */
    val scrollGroupId: String?,
)

data class TranslationBlock(
    val source: String,
    val translation: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Kept only in memory so translations can follow their scrollable container. */
    val scrollGroupId: String?,
)

data class TranslationResult(
    val blocks: List<TranslationBlock>,
)
