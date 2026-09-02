package pro.ivol.voicebridge.screen

/**
 * Repeats a transiently unavailable read until it becomes useful or its deadline expires.
 *
 * Android accessibility trees briefly disappear while an overlay is hidden or the active window
 * changes. Keeping the retry policy independent from Android makes its timing deterministic in
 * unit tests and keeps all waits on the caller's worker thread.
 */
internal fun <T : Any> retryTransientResultUntilDeadline(
    deadlineElapsedRealtimeMs: Long,
    retryDelayMs: Long,
    elapsedRealtimeMs: () -> Long,
    wait: (Long) -> Unit,
    isReady: (T) -> Boolean,
    read: () -> T?,
): T? {
    require(retryDelayMs > 0L) { "Retry delay must be positive." }

    var lastAvailableResult: T? = null
    while (elapsedRealtimeMs() < deadlineElapsedRealtimeMs) {
        val result = read()
        if (result != null) {
            lastAvailableResult = result
            if (isReady(result)) return result
        }

        val remainingMs = deadlineElapsedRealtimeMs - elapsedRealtimeMs()
        if (remainingMs <= 0L) break
        try {
            wait(minOf(retryDelayMs, remainingMs))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    }
    return lastAvailableResult
}
