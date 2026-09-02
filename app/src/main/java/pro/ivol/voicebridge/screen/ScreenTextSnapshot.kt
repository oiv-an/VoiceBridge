package pro.ivol.voicebridge.screen

/**
 * Text that Android exposes for one currently visible accessibility element.
 *
 * Coordinates are normalized to the whole physical display, not to an app window. This keeps
 * them directly compatible with the full-screen translation overlay.
 */
data class ScreenTextNode(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Local-only identity of the nearest scrollable ancestor, or null for fixed content. */
    val scrollGroupId: String?,
)

/** A point-in-time, immutable snapshot of text currently exposed by Android. */
data class ScreenTextSnapshot(
    val nodes: List<ScreenTextNode>,
)
