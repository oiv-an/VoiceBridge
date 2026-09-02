package pro.ivol.voicebridge.screen

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal interface ScreenInteractionListener {
    fun onContentScrolled(scrollGroupId: String?, deltaX: Int, deltaY: Int)

    fun onScreenChanged()

    fun onHostAppVisibilityChanged(visible: Boolean)
}

internal object ScreenInteractionBridge {
    private val listener = AtomicReference<ScreenInteractionListener?>(null)
    private val hostAppVisible = AtomicBoolean(false)
    private val accessibilityServiceInstance =
        AtomicReference<ScreenTrackingAccessibilityService?>(null)
    private val pendingBubbleClickEventTimeMs = AtomicLong(NO_EVENT_TIME)
    private val systemWindowEventsSuppressedUntilMs = AtomicLong(NO_EVENT_TIME)

    fun markAccessibilityServiceConnected(instance: ScreenTrackingAccessibilityService) {
        accessibilityServiceInstance.set(instance)
    }

    fun markAccessibilityServiceDisconnected(instance: ScreenTrackingAccessibilityService) {
        accessibilityServiceInstance.compareAndSet(instance, null)
    }

    fun isAccessibilityServiceConnected(): Boolean = accessibilityServiceInstance.get() != null

    /** Accessibility events are ignored unless the foreground overlay session is listening. */
    fun isSessionActive(): Boolean = listener.get() != null

    /**
     * Returns the visible text exposed by Android for the active app, synchronously.
     *
     * `null` means that the accessibility service or its active window is unavailable. An empty
     * snapshot means that the window was available but did not expose any usable visible text.
     */
    fun captureVisibleTextSnapshot(deadlineElapsedRealtimeMs: Long): ScreenTextSnapshot? =
        retryTransientResultUntilDeadline(
            deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            retryDelayMs = SNAPSHOT_RETRY_DELAY_MS,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            wait = Thread::sleep,
            isReady = { snapshot -> snapshot.nodes.isNotEmpty() },
            read = {
                accessibilityServiceInstance.get()?.captureVisibleTextSnapshot(
                    deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    rememberContentFingerprint = false,
                )
            },
        )

    fun activeWindowPackageName(): String? =
        accessibilityServiceInstance.get()?.activeWindowPackageName()

    fun rememberTranslatedSourceTexts(sourceTexts: List<String>) {
        accessibilityServiceInstance.get()?.rememberTranslatedSourceTexts(sourceTexts)
    }

    fun forgetProtectedContentFingerprint() {
        accessibilityServiceInstance.get()?.forgetProtectedContentFingerprint()
    }

    fun attach(value: ScreenInteractionListener) {
        listener.set(value)
        value.onHostAppVisibilityChanged(hostAppVisible.get())
    }

    fun detach(value: ScreenInteractionListener) {
        listener.compareAndSet(value, null)
    }

    fun dispatchScroll(scrollGroupId: String?, deltaX: Int, deltaY: Int) {
        listener.get()?.onContentScrolled(scrollGroupId, deltaX, deltaY)
    }

    fun dispatchScreenChanged() {
        listener.get()?.onScreenChanged()
    }

    fun setHostAppVisible(visible: Boolean) {
        hostAppVisible.set(visible)
        listener.get()?.onHostAppVisibilityChanged(visible)
    }

    fun isHostAppVisible(): Boolean = hostAppVisible.get()

    fun markBubbleClick(eventTimeMs: Long) {
        pendingBubbleClickEventTimeMs.set(eventTimeMs)
    }

    fun consumeMatchingBubbleClick(eventTimeMs: Long): Boolean {
        val markedAtMs = pendingBubbleClickEventTimeMs.get()
        if (markedAtMs == NO_EVENT_TIME) return false
        val observedAtMs = eventTimeMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val deltaMs = observedAtMs - markedAtMs
        if (deltaMs in 0L..BUBBLE_CLICK_MATCH_WINDOW_MS) {
            return pendingBubbleClickEventTimeMs.compareAndSet(markedAtMs, NO_EVENT_TIME)
        }
        if (SystemClock.uptimeMillis() - markedAtMs > BUBBLE_CLICK_TOKEN_MAX_AGE_MS) {
            pendingBubbleClickEventTimeMs.compareAndSet(markedAtMs, NO_EVENT_TIME)
        }
        return false
    }

    fun markSystemOverlayWindowMutation() {
        val deadline = SystemClock.uptimeMillis() + SYSTEM_WINDOW_EVENT_WINDOW_MS
        var previous: Long
        var updated: Long
        do {
            previous = systemWindowEventsSuppressedUntilMs.get()
            updated = maxOf(previous, deadline)
        } while (!systemWindowEventsSuppressedUntilMs.compareAndSet(previous, updated))
    }

    fun shouldSuppressSystemOverlayWindowEvent(
        sourcePackage: String,
        eventTimeMs: Long,
    ): Boolean {
        if (sourcePackage !in SYSTEM_OVERLAY_PACKAGES) return false
        val observedAtMs = eventTimeMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        return observedAtMs <= systemWindowEventsSuppressedUntilMs.get()
    }

    private const val NO_EVENT_TIME = -1L
    private const val BUBBLE_CLICK_MATCH_WINDOW_MS = 150L
    private const val BUBBLE_CLICK_TOKEN_MAX_AGE_MS = 1_500L
    private const val SYSTEM_WINDOW_EVENT_WINDOW_MS = 700L
    private const val SNAPSHOT_RETRY_DELAY_MS = 75L
    private val SYSTEM_OVERLAY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.huawei.systemui",
    )
}

internal fun Context.isScreenTrackingAccessibilityEnabled(): Boolean {
    return isScreenTrackingAccessibilityConfigured() &&
        ScreenInteractionBridge.isAccessibilityServiceConnected()
}

internal fun Context.isScreenTrackingAccessibilityConfigured(): Boolean {
    if (
        Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) != 1
    ) return false

    val expected = ComponentName(this, ScreenTrackingAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { flattened ->
        ComponentName.unflattenFromString(flattened) == expected
    }
}
