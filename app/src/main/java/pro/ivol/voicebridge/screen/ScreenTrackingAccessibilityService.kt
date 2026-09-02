package pro.ivol.voicebridge.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import pro.ivol.voicebridge.MainActivity

class ScreenTrackingAccessibilityService : AccessibilityService() {
    private data class ScrollPosition(val x: Int, val y: Int, val recordedAtMs: Long)

    private data class ActiveScreenIdentity(
        val packageName: String,
        val windowId: Int,
    )

    /**
     * A local-only multiset of visible text anchors. It is never logged or persisted.
     *
     * Keeping only text anchors, instead of the whole accessibility tree, lets us distinguish an
     * actual page replacement from Chrome/Android emitting a routine content-change event.
     */
    private data class ContentFingerprint(
        val textCounts: Map<String, Int>,
    ) {
        val totalCount: Int = textCounts.values.sum()

        fun retainedRatioIn(current: ContentFingerprint): Double {
            if (totalCount == 0) return if (current.totalCount == 0) 1.0 else 0.0
            val retained = textCounts.entries.sumOf { (text, expectedCount) ->
                min(expectedCount, current.textCounts[text] ?: 0)
            }
            return retained.toDouble() / totalCount.toDouble()
        }

        fun retainVisibleIn(current: ContentFingerprint): ContentFingerprint? {
            val retained = buildMap {
                textCounts.forEach { (text, expectedCount) ->
                    val visibleCount = min(expectedCount, current.textCounts[text] ?: 0)
                    if (visibleCount > 0) put(text, visibleCount)
                }
            }
            return retained.takeIf { it.isNotEmpty() }?.let(::ContentFingerprint)
        }

        companion object {
            fun fromTexts(texts: List<String>): ContentFingerprint? {
                val counts = texts.asSequence()
                    .filter(String::isNotBlank)
                    .groupingBy { it }
                    .eachCount()
                return counts.takeIf { it.isNotEmpty() }?.let(::ContentFingerprint)
            }
        }
    }

    private data class ExtractedTextNode(
        val text: String,
        val bounds: Rect,
        val scrollGroupId: String?,
    )

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val inheritedScrollGroupId: String?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scrollPositions = HashMap<String, ScrollPosition>()
    @Volatile
    private var lastPackageName: String? = null

    @Volatile
    private var lastObservedWindowId = UNKNOWN_WINDOW_ID
    private var lastScrollEventAtMs = NO_EVENT_TIME
    private var contentChangePending = false
    private var protectedContentFingerprint: ContentFingerprint? = null

    private val contentChangeRunnable = Runnable {
        contentChangePending = false
        validateStableContentChange()
    }

    private val refreshFingerprintAfterScrollRunnable = Runnable {
        refreshProtectedFingerprintAfterScroll()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenInteractionBridge.markAccessibilityServiceConnected(this)
        resetTrackedScreen("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!ScreenInteractionBridge.isSessionActive()) return
        val sourcePackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            ScreenInteractionBridge.consumeMatchingBubbleClick(event.eventTime)
        ) {
            Log.d(TAG, "screen_event_ignored reason=bubble_click")
            return
        }
        if (sourcePackage == packageName) {
            if (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.className?.toString() == MainActivity::class.java.name
            ) {
                clearTranslationAndScrollState("own_activity_opened")
            }
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ScreenInteractionBridge.shouldSuppressSystemOverlayWindowEvent(
                sourcePackage,
                event.eventTime,
            )
        ) {
            Log.d(TAG, "screen_event_ignored reason=system_overlay_window")
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            synchronizeTrackedScreenWithActiveRoot()
            return
        }

        if (!eventBelongsToActiveScreen(sourcePackage, event.windowId)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                markScrollEvent()
                handleScroll(event, sourcePackage, nowMs)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A click in the underlying app may change content without changing Android's
                // window id. Clear immediately; clicks on our RU overlay are ignored above.
                clearTranslationAndScrollState("view_clicked")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> scheduleStableContentChange()

            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenInteractionBridge.markAccessibilityServiceDisconnected(this)
        resetTrackedScreen("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenInteractionBridge.markAccessibilityServiceDisconnected(this)
        resetTrackedScreen("destroyed")
        super.onDestroy()
    }

    /**
     * Reads a self-contained snapshot from the active accessibility tree.
     *
     * The returned model contains no live [AccessibilityNodeInfo] references, so it is safe to
     * pass to a worker thread after this method returns. Failures caused by a window disappearing
     * during traversal are treated as an unavailable snapshot instead of escaping to the caller.
     */
    internal fun captureVisibleTextSnapshot(
        deadlineElapsedRealtimeMs: Long,
        rememberContentFingerprint: Boolean = true,
    ): ScreenTextSnapshot? {
        val root = obtainActiveApplicationRoot() ?: return null
        val rootPackage = root.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (rootPackage == null || rootPackage == packageName) {
            root.recycleSafely()
            return null
        }
        lastPackageName = rootPackage
        if (root.windowId != UNKNOWN_WINDOW_ID) lastObservedWindowId = root.windowId
        val displayBounds = physicalDisplayBounds()
        if (displayBounds.isEmpty) {
            root.recycleSafely()
            return null
        }

        val extracted = ArrayList<ExtractedTextNode>()
        val pending = ArrayDeque<PendingNode>()
        pending.add(PendingNode(root, inheritedScrollGroupId = null))
        var visitedCount = 0
        var deadlineExceeded = false

        try {
            while (
                pending.isNotEmpty() &&
                visitedCount < MAX_VISITED_NODES &&
                extracted.size < MAX_RESULT_NODES
            ) {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
                    deadlineExceeded = true
                    break
                }
                val pendingNode = pending.removeFirst()
                val node = pendingNode.node
                visitedCount += 1
                try {
                    val scrollGroupId = if (isScrollableNode(node)) {
                        localScrollGroupId(node)
                    } else {
                        pendingNode.inheritedScrollGroupId
                    }
                    enqueueChildren(
                        node = node,
                        inheritedScrollGroupId = scrollGroupId,
                        pending = pending,
                        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    )
                    extractVisibleTextNode(node, displayBounds, scrollGroupId)?.let(extracted::add)
                } finally {
                    node.recycleSafely()
                }
            }
        } catch (_: SecurityException) {
            recyclePendingNodes(pending)
            return null
        } catch (_: IllegalStateException) {
            // The active window may be replaced while its accessibility tree is being traversed.
            recyclePendingNodes(pending)
            return null
        } finally {
            recyclePendingNodes(pending)
        }
        if (deadlineExceeded && extracted.isEmpty()) return null

        val nodes = deduplicate(extracted)
            .sortedWith(
                compareBy<ExtractedTextNode>(
                    { it.bounds.top },
                    { it.bounds.left },
                    { it.bounds.bottom },
                    { it.bounds.right },
                    { it.text },
                ),
            )
            .take(MAX_RESULT_NODES)
            .map { it.toPublicNode(displayBounds) }
        return ScreenTextSnapshot(nodes).also { snapshot ->
            if (rememberContentFingerprint) {
                synchronized(snapshotLock) {
                    protectedContentFingerprint =
                        ContentFingerprint.fromTexts(snapshot.nodes.map(ScreenTextNode::text))
                }
            }
        }
    }

    internal fun activeWindowPackageName(): String? {
        val root = obtainActiveApplicationRoot() ?: return null
        return try {
            root.packageName?.toString()?.takeIf(String::isNotBlank)
        } finally {
            root.recycleSafely()
        }
    }

    internal fun rememberTranslatedSourceTexts(sourceTexts: List<String>) {
        synchronized(snapshotLock) {
            protectedContentFingerprint = ContentFingerprint.fromTexts(sourceTexts)
        }
    }

    internal fun forgetProtectedContentFingerprint() {
        synchronized(snapshotLock) {
            protectedContentFingerprint = null
        }
    }

    private fun enqueueChildren(
        node: AccessibilityNodeInfo,
        inheritedScrollGroupId: String?,
        pending: ArrayDeque<PendingNode>,
        deadlineElapsedRealtimeMs: Long,
    ) {
        val childCount = min(node.childCount, MAX_CHILDREN_PER_NODE)
        for (index in 0 until childCount) {
            if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            pending.addLast(PendingNode(child, inheritedScrollGroupId))
        }
    }

    /**
     * `rootInActiveWindow` can briefly be null on EMUI while our overlay changes visibility.
     * Interactive-window lookup is a safe fallback because the service explicitly requests it.
     */
    private fun obtainActiveApplicationRoot(): AccessibilityNodeInfo? {
        runCatching { rootInActiveWindow }.getOrNull()?.let { return it }

        val availableWindows = runCatching { windows }.getOrNull().orEmpty()
        try {
            return availableWindows.asSequence()
                .filter { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (window.isActive || window.isFocused)
                }
                .sortedByDescending { window -> if (window.isActive) 1 else 0 }
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
                .firstOrNull()
        } finally {
            availableWindows.forEach { window -> window.recycleSafely() }
        }
    }

    private fun extractVisibleTextNode(
        node: AccessibilityNodeInfo,
        displayBounds: Rect,
        scrollGroupId: String?,
    ): ExtractedTextNode? {
        if (!node.isVisibleToUser || node.isPassword) return null
        if (node.packageName?.toString() == packageName) return null

        val text = normalizedNodeText(node) ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !bounds.intersect(displayBounds)) return null

        return ExtractedTextNode(text, bounds, scrollGroupId)
    }

    private fun normalizedNodeText(node: AccessibilityNodeInfo): String? {
        val primary = normalizeText(node.text)
        if (primary != null) return primary
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            node.isShowingHintText
        ) {
            normalizeText(node.hintText)
        } else {
            null
        }
    }

    private fun normalizeText(value: CharSequence?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = buildString(min(value.length, MAX_TEXT_LENGTH)) {
            var previousWasWhitespace = true
            value.forEach { character ->
                if (length >= MAX_TEXT_LENGTH) return@forEach
                if (character.isWhitespace()) {
                    if (!previousWasWhitespace) append(' ')
                    previousWasWhitespace = true
                } else if (!character.isISOControl()) {
                    append(character)
                    previousWasWhitespace = false
                }
            }
        }.trim()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun deduplicate(nodes: List<ExtractedTextNode>): List<ExtractedTextNode> {
        if (nodes.size < 2) return nodes

        val result = ArrayList<ExtractedTextNode>(nodes.size)
        nodes.forEach { candidate ->
            val duplicateIndex = result.indexOfFirst { existing ->
                existing.text == candidate.text && boundsAreDuplicates(existing.bounds, candidate.bounds)
            }
            if (duplicateIndex < 0) {
                result.add(candidate)
            } else if (candidate.bounds.area() < result[duplicateIndex].bounds.area()) {
                // Parent and child nodes often expose identical text. The tighter rectangle is a
                // better anchor for the translated label.
                result[duplicateIndex] = candidate
            }
        }
        return result
    }

    private fun boundsAreDuplicates(first: Rect, second: Rect): Boolean {
        if (first == second) return true
        val intersectionLeft = max(first.left, second.left)
        val intersectionTop = max(first.top, second.top)
        val intersectionRight = min(first.right, second.right)
        val intersectionBottom = min(first.bottom, second.bottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return false
        }

        val intersectionArea =
            (intersectionRight - intersectionLeft).toLong() *
                (intersectionBottom - intersectionTop).toLong()
        val smallerArea = min(first.area(), second.area()).coerceAtLeast(1L)
        return intersectionArea.toDouble() / smallerArea.toDouble() >= DUPLICATE_OVERLAP_RATIO
    }

    private fun ExtractedTextNode.toPublicNode(displayBounds: Rect): ScreenTextNode {
        val displayWidth = displayBounds.width().toFloat().coerceAtLeast(1f)
        val displayHeight = displayBounds.height().toFloat().coerceAtLeast(1f)
        val normalizedLeft = ((bounds.left - displayBounds.left) / displayWidth).coerceIn(0f, 1f)
        val normalizedTop = ((bounds.top - displayBounds.top) / displayHeight).coerceIn(0f, 1f)
        val normalizedRight = ((bounds.right - displayBounds.left) / displayWidth).coerceIn(0f, 1f)
        val normalizedBottom = ((bounds.bottom - displayBounds.top) / displayHeight).coerceIn(0f, 1f)
        return ScreenTextNode(
            text = text,
            x = normalizedLeft,
            y = normalizedTop,
            width = (normalizedRight - normalizedLeft).coerceIn(0f, 1f),
            height = (normalizedBottom - normalizedTop).coerceIn(0f, 1f),
            scrollGroupId = scrollGroupId,
        )
    }

    private fun physicalDisplayBounds(): Rect {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Rect(windowManager.maximumWindowMetrics.bounds)
        }

        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleSafely() {
        runCatching { recycle() }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.recycleSafely() {
        runCatching { recycle() }
    }

    private fun recyclePendingNodes(pending: ArrayDeque<PendingNode>) {
        while (pending.isNotEmpty()) pending.removeFirst().node.recycleSafely()
    }

    private fun Rect.area(): Long = width().toLong() * height().toLong()

    private fun handleScroll(
        event: AccessibilityEvent,
        sourcePackage: String,
        nowMs: Long,
    ) {
        val scrollGroupId = scrollGroupIdForEvent(event)
        if (scrollGroupId == null) {
            clearTranslationAndScrollState("unknown_scroll_group")
            return
        }

        val key = scrollPositionKey(sourcePackage, scrollGroupId)
        val currentPosition = ScrollPosition(event.scrollX, event.scrollY, nowMs)
        val previous = scrollPositions.put(key, currentPosition)

        val reportedDeltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaX
        } else {
            ACCESSIBILITY_VALUE_UNDEFINED
        }
        val reportedDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaY
        } else {
            ACCESSIBILITY_VALUE_UNDEFINED
        }

        val recentPrevious = previous?.takeIf {
            nowMs - it.recordedAtMs <= SCROLL_POSITION_MAX_AGE_MS
        }
        val positionDeltaX = recentPrevious?.let {
            if (event.scrollX >= 0 && it.x >= 0) event.scrollX - it.x else null
        }
        val positionDeltaY = recentPrevious?.let {
            if (event.scrollY >= 0 && it.y >= 0) event.scrollY - it.y else null
        }
        val deltaX = when {
            reportedDeltaX != ACCESSIBILITY_VALUE_UNDEFINED && reportedDeltaX != 0 ->
                reportedDeltaX
            positionDeltaX != null -> positionDeltaX
            else -> 0
        }
        val deltaY = when {
            reportedDeltaY != ACCESSIBILITY_VALUE_UNDEFINED && reportedDeltaY != 0 ->
                reportedDeltaY
            positionDeltaY != null -> positionDeltaY
            else -> 0
        }

        if (deltaX == 0 && deltaY == 0) {
            // EMUI often reports the first scroll without a usable delta. Keeping labels in their
            // old coordinates would be incorrect, so clear them while retaining this new baseline.
            clearTranslationAndScrollState("unknown_scroll_delta")
            scrollPositions[key] = currentPosition
            return
        }
        val displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        if (
            kotlin.math.abs(deltaX) >= displayWidth ||
            kotlin.math.abs(deltaY) >= displayHeight
        ) {
            clearTranslationAndScrollState("oversized_scroll")
            return
        }
        if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
            clearTranslationAndScrollState("horizontal_scroll")
            return
        }
        Log.d(TAG, "content_scrolled dx=$deltaX dy=$deltaY")
        ScreenInteractionBridge.dispatchScroll(scrollGroupId, deltaX, deltaY)
    }

    /**
     * AccessibilityEvent.windowId is the window that produced an event, not necessarily the
     * active application window. Verify every mismatch against rootInActiveWindow before changing
     * tracked state so IME/SystemUI/overlay events cannot remove a valid translation.
     */
    private fun eventBelongsToActiveScreen(sourcePackage: String, eventWindowId: Int): Boolean {
        if (
            sourcePackage == lastPackageName &&
            windowIdsMatch(eventWindowId, lastObservedWindowId)
        ) {
            return true
        }

        val activeIdentity = activeScreenIdentity()
        if (activeIdentity == null) {
            Log.d(TAG, "screen_event_ignored reason=active_root_unavailable")
            return false
        }

        val screenChanged = trackedScreenDiffersFrom(activeIdentity)
        updateTrackedScreen(activeIdentity)
        if (screenChanged) {
            clearTranslationAndScrollState("active_root_changed")
            return false
        }

        val belongsToActiveScreen =
            sourcePackage == activeIdentity.packageName &&
                windowIdsMatch(eventWindowId, activeIdentity.windowId)
        if (!belongsToActiveScreen) {
            Log.d(TAG, "screen_event_ignored reason=foreign_event_window")
        }
        return belongsToActiveScreen
    }

    private fun synchronizeTrackedScreenWithActiveRoot() {
        val activeIdentity = activeScreenIdentity()
        if (activeIdentity == null) {
            Log.d(TAG, "screen_event_ignored reason=active_root_unavailable")
            return
        }

        val screenChanged = trackedScreenDiffersFrom(activeIdentity)
        updateTrackedScreen(activeIdentity)
        if (screenChanged) clearTranslationAndScrollState("active_root_changed")
    }

    private fun activeScreenIdentity(): ActiveScreenIdentity? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return try {
            val activePackage = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: return null
            if (activePackage == packageName) return null
            ActiveScreenIdentity(activePackage, root.windowId)
        } finally {
            root.recycleSafely()
        }
    }

    private fun trackedScreenDiffersFrom(identity: ActiveScreenIdentity): Boolean {
        val packageChanged = lastPackageName != null && lastPackageName != identity.packageName
        val windowChanged = !windowIdsMatch(lastObservedWindowId, identity.windowId)
        return packageChanged || windowChanged
    }

    private fun updateTrackedScreen(identity: ActiveScreenIdentity) {
        lastPackageName = identity.packageName
        if (identity.windowId != UNKNOWN_WINDOW_ID) lastObservedWindowId = identity.windowId
    }

    private fun windowIdsMatch(first: Int, second: Int): Boolean =
        first == UNKNOWN_WINDOW_ID || second == UNKNOWN_WINDOW_ID || first == second

    private fun validateStableContentChange() {
        val expected = synchronized(snapshotLock) { protectedContentFingerprint }
        if (expected == null) {
            Log.d(TAG, "screen_event_ignored reason=no_translation_fingerprint")
            return
        }

        val activeIdentity = activeScreenIdentity()
        if (activeIdentity == null) {
            Log.d(TAG, "screen_event_ignored reason=content_root_unavailable")
            return
        }
        if (trackedScreenDiffersFrom(activeIdentity)) {
            updateTrackedScreen(activeIdentity)
            clearTranslationAndScrollState("active_root_changed")
            return
        }

        val currentSnapshot = captureVisibleTextSnapshot(
            deadlineElapsedRealtimeMs =
                SystemClock.elapsedRealtime() + CONTENT_VALIDATION_DEADLINE_MS,
            rememberContentFingerprint = false,
        )
        if (currentSnapshot == null) {
            Log.d(TAG, "screen_event_ignored reason=content_snapshot_unavailable")
            return
        }
        val current = ContentFingerprint.fromTexts(currentSnapshot.nodes.map(ScreenTextNode::text))
        if (current == null) {
            clearTranslationAndScrollState("visible_text_removed")
            return
        }

        val retainedRatio = expected.retainedRatioIn(current)
        if (retainedRatio >= MIN_PROTECTED_TEXT_RETENTION_RATIO) {
            Log.d(TAG, "screen_event_ignored reason=visible_text_unchanged")
        } else {
            clearTranslationAndScrollState("visible_text_changed")
        }
    }

    private fun refreshProtectedFingerprintAfterScroll() {
        val expected = synchronized(snapshotLock) { protectedContentFingerprint } ?: return
        val currentSnapshot = captureVisibleTextSnapshot(
            deadlineElapsedRealtimeMs =
                SystemClock.elapsedRealtime() + CONTENT_VALIDATION_DEADLINE_MS,
            rememberContentFingerprint = false,
        ) ?: return
        val current = ContentFingerprint.fromTexts(currentSnapshot.nodes.map(ScreenTextNode::text))
            ?: return
        synchronized(snapshotLock) {
            if (protectedContentFingerprint == expected) {
                protectedContentFingerprint = expected.retainVisibleIn(current)
            }
        }
    }

    private fun markScrollEvent() {
        lastScrollEventAtMs = SystemClock.uptimeMillis()
        cancelPendingContentChange()
        mainHandler.removeCallbacks(refreshFingerprintAfterScrollRunnable)
        mainHandler.postDelayed(
            refreshFingerprintAfterScrollRunnable,
            FINGERPRINT_REFRESH_AFTER_SCROLL_MS,
        )
    }

    private fun scheduleStableContentChange() {
        val sinceScrollMs = SystemClock.uptimeMillis() - lastScrollEventAtMs
        if (lastScrollEventAtMs != NO_EVENT_TIME && sinceScrollMs in 0L..CONTENT_AFTER_SCROLL_SUPPRESSION_MS) {
            Log.d(TAG, "screen_event_ignored reason=content_near_scroll")
            return
        }

        mainHandler.removeCallbacks(contentChangeRunnable)
        contentChangePending = true
        mainHandler.postDelayed(contentChangeRunnable, CONTENT_CHANGE_DEBOUNCE_MS)
    }

    private fun cancelPendingContentChange() {
        if (!contentChangePending) return
        contentChangePending = false
        mainHandler.removeCallbacks(contentChangeRunnable)
    }

    private fun scrollGroupIdForEvent(event: AccessibilityEvent): String? {
        var current = runCatching { event.source }.getOrNull() ?: return null
        var depth = 0
        while (depth < MAX_PARENT_DEPTH) {
            if (isScrollableNode(current)) {
                val groupId = localScrollGroupId(current)
                current.recycleSafely()
                return groupId
            }
            val parent = runCatching { current.parent }.getOrNull()
            current.recycleSafely()
            current = parent ?: return null
            depth += 1
        }
        current.recycleSafely()
        return null
    }

    private fun isScrollableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        return node.actionList.any { action -> action.id in SCROLL_ACTION_IDS }
    }

    private fun localScrollGroupId(node: AccessibilityNodeInfo): String = buildString {
        append(node.windowId)
        append(':')
        append(node.hashCode())
    }

    private fun scrollPositionKey(sourcePackage: String, scrollGroupId: String): String = buildString {
        append(sourcePackage)
        append('|')
        append(scrollGroupId)
    }

    private fun clearTranslationAndScrollState(reason: String) {
        cancelPendingContentChange()
        mainHandler.removeCallbacks(refreshFingerprintAfterScrollRunnable)
        scrollPositions.clear()
        forgetProtectedContentFingerprint()
        Log.d(TAG, "translation_cleared reason=$reason")
        ScreenInteractionBridge.dispatchScreenChanged()
    }

    private fun resetTrackedScreen(reason: String) {
        lastPackageName = null
        lastObservedWindowId = UNKNOWN_WINDOW_ID
        lastScrollEventAtMs = NO_EVENT_TIME
        clearTranslationAndScrollState(reason)
    }

    private companion object {
        const val TAG = "ScreenScrollTracker"
        const val ACCESSIBILITY_VALUE_UNDEFINED = -1
        const val UNKNOWN_WINDOW_ID = -1
        const val NO_EVENT_TIME = -1L
        const val SCROLL_POSITION_MAX_AGE_MS = 1_000L
        const val CONTENT_AFTER_SCROLL_SUPPRESSION_MS = 750L
        const val CONTENT_CHANGE_DEBOUNCE_MS = 250L
        const val CONTENT_VALIDATION_DEADLINE_MS = 750L
        const val FINGERPRINT_REFRESH_AFTER_SCROLL_MS = 500L
        const val MIN_PROTECTED_TEXT_RETENTION_RATIO = 0.8
        const val MAX_VISITED_NODES = 2_000
        const val MAX_CHILDREN_PER_NODE = 500
        const val MAX_PARENT_DEPTH = 64
        const val MAX_RESULT_NODES = 80
        const val MAX_TEXT_LENGTH = 2_000
        const val DUPLICATE_OVERLAP_RATIO = 0.9
        val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        )
    }

    private val snapshotLock = Any()
}
