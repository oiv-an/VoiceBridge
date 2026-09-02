package pro.ivol.voicebridge.screen

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import pro.ivol.voicebridge.R

/** Draws the screen-translation controls and labels without owning translation logic. */
class OverlayController(
    context: Context,
    private val onTranslate: () -> Unit,
    private val onCancelTranslation: () -> Unit,
    private val onStop: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onTranslationsCleared: () -> Unit = {},
    private val onTranslationsDisplayed: (List<TranslationBlock>) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bubbleSize = dp(48)
    private val appButtonSize = dp(40)
    private val controlGap = dp(4)
    private val controlHeight = bubbleSize + controlGap + appButtonSize
    private val edgeMargin = dp(10)
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    private val bubbleView = HoldProgressTextView(appContext).apply {
        text = appContext.getString(R.string.screen_translation_bubble_label)
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        elevation = dp(8).toFloat()
        contentDescription = IDLE_BUBBLE_DESCRIPTION
        background = bubbleBackground(BUBBLE_COLOR)
        setOnClickListener { handleBubbleClick() }
    }
    private val appButton = ImageButton(appContext).apply {
        setImageResource(R.drawable.ic_voicebridge_overlay_home)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = bubbleBackground(APP_BUTTON_COLOR)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        elevation = dp(6).toFloat()
        contentDescription = SETTINGS_DESCRIPTION
        setOnClickListener {
            if (!destroyed && isEnabled) onOpenSettings()
        }
    }
    private val controlRoot = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(
            bubbleView,
            LinearLayout.LayoutParams(bubbleSize, bubbleSize),
        )
        addView(
            appButton,
            LinearLayout.LayoutParams(appButtonSize, appButtonSize).apply {
                topMargin = controlGap
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
    }
    private val translationView = TranslationOverlayView(appContext)

    private val bubbleParams = WindowManager.LayoutParams(
        bubbleSize,
        controlHeight,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "VoiceBridge screen translator control"
    }

    private val translationParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // On Android 12+, a non-touchable application overlay above 0.8 blocks
        // touches to the app underneath it. This must be LayoutParams.alpha,
        // not merely View.alpha.
        alpha = safeOverlayAlpha()
        title = "VoiceBridge screen translation labels"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private var bubbleAdded = false
    private var translationsAdded = false
    private var destroyed = false
    private var busy = false
    private var hiddenForCapture = false
    private var appButtonVisible = true
    private var restoreBubbleAfterCapture = false
    private var restoreTranslationsAfterCapture = false

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            runOnMain {
                if (destroyed) return@runOnMain
                clearTranslationsInternal()
                clampBubbleToSafeArea()
                translationView.requestApplyInsets()
                translationView.invalidate()
            }
        }

        override fun onLowMemory() = Unit
    }

    init {
        appContext.registerComponentCallbacks(componentCallbacks)
        installBubbleTouchHandler()
    }

    fun showBubble(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || destroyed) return false
        if (bubbleAdded) {
            if (!hiddenForCapture) {
                controlRoot.visibility = View.VISIBLE
                controlRoot.alpha = 1f
                bubbleView.isEnabled = true
                appButton.isEnabled = appButtonVisible
            }
            return ensureTranslationWindow()
        }

        val safeArea = safeDisplayArea()
        bubbleParams.x = max(safeArea.left + edgeMargin, safeArea.right - bubbleSize - edgeMargin)
        bubbleParams.y = (safeArea.top + safeArea.height() * 0.35f).toInt()

        try {
            ScreenInteractionBridge.markSystemOverlayWindowMutation()
            windowManager.addView(controlRoot, bubbleParams)
            bubbleAdded = true
            controlRoot.visibility = View.VISIBLE
            controlRoot.alpha = if (hiddenForCapture) 0f else 1f
            bubbleView.isEnabled = !hiddenForCapture
            appButton.isEnabled = appButtonVisible && !hiddenForCapture
            if (hiddenForCapture) restoreBubbleAfterCapture = true
            controlRoot.post(::clampBubbleToSafeArea)
            return ensureTranslationWindow()
        } catch (_: Exception) {
            showErrorInternal(OVERLAY_PERMISSION_ERROR)
            return false
        }
    }

    fun setBusy(value: Boolean) = runOnMain {
        if (destroyed) return@runOnMain
        busy = value
        mainHandler.removeCallbacks(resetBubbleStateRunnable)
        bubbleView.text = if (value) {
            "…"
        } else {
            appContext.getString(R.string.screen_translation_bubble_label)
        }
        bubbleView.contentDescription = if (value) {
            BUSY_BUBBLE_DESCRIPTION
        } else {
            IDLE_BUBBLE_DESCRIPTION
        }
        bubbleView.background = bubbleBackground(if (value) BUBBLE_BUSY_COLOR else BUBBLE_COLOR)
        bubbleView.alpha = if (value) 0.88f else 1f
    }

    fun setAppButtonVisible(visible: Boolean) = runOnMain {
        if (destroyed || appButtonVisible == visible) return@runOnMain
        appButtonVisible = visible
        appButton.visibility = if (visible) View.VISIBLE else View.GONE
        appButton.isEnabled = visible && !hiddenForCapture
        bubbleParams.height = currentControlHeight()
        if (bubbleAdded) {
            try {
                ScreenInteractionBridge.markSystemOverlayWindowMutation()
                windowManager.updateViewLayout(controlRoot, bubbleParams)
            } catch (_: Exception) {
                // The system may be removing the overlay while the service stops.
            }
            clampBubbleToSafeArea()
        }
    }

    fun hideForCapture() = runOnMain {
        if (destroyed || hiddenForCapture) return@runOnMain
        hiddenForCapture = true
        restoreBubbleAfterCapture = bubbleAdded && controlRoot.alpha > 0f
        restoreTranslationsAfterCapture =
            translationsAdded && translationView.alpha > 0f && translationView.hasBlocks()

        if (bubbleAdded) {
            controlRoot.alpha = 0f
            bubbleView.isEnabled = false
            appButton.isEnabled = false
        }
        if (translationsAdded) translationView.alpha = 0f
    }

    fun restoreBubble() = runOnMain {
        if (destroyed || !hiddenForCapture) return@runOnMain
        hiddenForCapture = false

        if (bubbleAdded) {
            controlRoot.visibility = View.VISIBLE
            controlRoot.alpha = if (restoreBubbleAfterCapture) 1f else 0f
            bubbleView.isEnabled = restoreBubbleAfterCapture
            appButton.isEnabled = appButtonVisible && restoreBubbleAfterCapture
        }
        if (translationsAdded && restoreTranslationsAfterCapture && translationView.hasBlocks()) {
            translationView.alpha = 1f
        }

        restoreBubbleAfterCapture = false
        restoreTranslationsAfterCapture = false
        clampBubbleToSafeArea()
    }

    fun showTranslations(blocks: List<TranslationBlock>) = runOnMain {
        if (destroyed) return@runOnMain
        if (blocks.isEmpty()) {
            clearTranslationsInternal()
            notifyTranslationsDisplayed(emptyList())
            showErrorInternal(NO_TEXT_ERROR)
            showTemporaryBubbleError()
            return@runOnMain
        }

        val hadTranslations = translationView.hasBlocks()
        val acceptedBlocks = translationView.setBlocks(blocks)
        Log.i(TAG, "translation_overlay_blocks input=${blocks.size} accepted=${acceptedBlocks.size}")
        if (acceptedBlocks.isEmpty()) {
            clearTranslationsInternal(hadTranslationsBeforeClear = hadTranslations)
            notifyTranslationsDisplayed(emptyList())
            showErrorInternal(NO_USEFUL_TRANSLATION_ERROR)
            showTemporaryBubbleError()
            return@runOnMain
        }

        if (!ensureTranslationWindow()) {
            clearTranslationsInternal()
            notifyTranslationsDisplayed(emptyList())
            showTemporaryBubbleError()
            return@runOnMain
        }

        if (hiddenForCapture) {
            restoreTranslationsAfterCapture = true
            translationView.alpha = 0f
        } else {
            translationView.alpha = 1f
            bubbleView.contentDescription = TRANSLATION_VISIBLE_BUBBLE_DESCRIPTION
        }
        notifyTranslationsDisplayed(acceptedBlocks)
    }

    fun showError(message: String) = runOnMain {
        if (!destroyed) {
            showErrorInternal(message)
            showTemporaryBubbleError()
        }
    }

    fun clearTranslations() = runOnMain {
        if (!destroyed) clearTranslationsInternal()
    }

    fun moveTranslationsWithContent(scrollGroupId: String?, deltaX: Int, deltaY: Int) = runOnMain {
        if (!destroyed && !translationView.moveWithContent(scrollGroupId, deltaX, deltaY)) {
            clearTranslationsInternal()
        }
    }

    fun destroy() = runOnMain {
        if (destroyed) return@runOnMain
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        clearTranslationsInternal()

        if (bubbleAdded) {
            try {
                windowManager.removeViewImmediate(controlRoot)
            } catch (_: Exception) {
                // The system may already have removed the overlay.
            }
            bubbleAdded = false
        }

        if (translationsAdded) {
            try {
                windowManager.removeViewImmediate(translationView)
            } catch (_: Exception) {
                // The system may already have removed the overlay.
            }
            translationsAdded = false
        }

        try {
            appContext.unregisterComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {
            // Safe when destroy is called during process teardown.
        }
    }

    private fun ensureTranslationWindow(): Boolean {
        if (translationsAdded && translationView.isAttachedToWindow) return true
        if (destroyed) return false
        if (translationsAdded) {
            runCatching { windowManager.removeViewImmediate(translationView) }
            translationsAdded = false
        }
        try {
            ScreenInteractionBridge.markSystemOverlayWindowMutation()
            windowManager.addView(translationView, translationParams)
            translationsAdded = true
            translationView.visibility = View.VISIBLE
            translationView.alpha = 0f
            translationView.requestApplyInsets()
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to add translation overlay (${error.javaClass.simpleName})")
            showErrorInternal(TRANSLATION_OVERLAY_ERROR)
            return false
        }
    }

    /**
     * Clears the visual state before notifying the owner. If the owner responds by
     * calling [clearTranslations] again, the empty-state check prevents recursion.
     */
    private fun clearTranslationsInternal(
        hadTranslationsBeforeClear: Boolean = translationView.hasBlocks(),
    ) {
        translationView.setBlocks(emptyList())
        restoreTranslationsAfterCapture = false
        if (translationsAdded) translationView.alpha = 0f
        if (!busy) bubbleView.contentDescription = IDLE_BUBBLE_DESCRIPTION
        if (hadTranslationsBeforeClear) {
            runCatching(onTranslationsCleared).onFailure { error ->
                Log.e(TAG, "Translation clear callback failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun notifyTranslationsDisplayed(acceptedBlocks: List<TranslationBlock>) {
        runCatching { onTranslationsDisplayed(acceptedBlocks) }.onFailure { error ->
            Log.e(TAG, "Translation display callback failed (${error.javaClass.simpleName})")
        }
    }

    private fun handleBubbleClick() {
        if (busy || destroyed) return
        if (
            translationsAdded &&
            translationView.visibility == View.VISIBLE &&
            translationView.hasBlocks()
        ) {
            clearTranslationsInternal()
            return
        }
        onTranslate()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installBubbleTouchHandler() {
        var downRawX = 0f
        var downRawY = 0f
        var holdStartedAtMs = 0L
        var startX = 0
        var startY = 0
        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var moved = false
        var holdActive = false
        var longPressThresholdReached = false
        var stopTriggered = false

        lateinit var progressTick: Runnable
        lateinit var longPressThreshold: Runnable
        lateinit var stopAfterFullHold: Runnable

        fun cancelHoldTracking() {
            holdActive = false
            mainHandler.removeCallbacks(longPressThreshold)
            mainHandler.removeCallbacks(stopAfterFullHold)
            bubbleView.removeCallbacks(progressTick)
            bubbleView.clearHoldProgress()
        }

        fun reachLongPressThreshold() {
            if (!holdActive || moved || stopTriggered || longPressThresholdReached) return
            longPressThresholdReached = true
            if (busy) onCancelTranslation()
        }

        fun stopAfterThreeSeconds() {
            if (!holdActive || moved || stopTriggered || destroyed || !bubbleAdded) return
            stopTriggered = true
            longPressThresholdReached = true
            holdActive = false
            mainHandler.removeCallbacks(longPressThreshold)
            mainHandler.removeCallbacks(stopAfterFullHold)
            bubbleView.removeCallbacks(progressTick)
            bubbleView.completeHoldProgress()
            bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onStop()
        }

        progressTick = Runnable {
            if (!holdActive || moved || stopTriggered || destroyed || !bubbleAdded) return@Runnable
            val elapsedMs = (SystemClock.uptimeMillis() - holdStartedAtMs).coerceAtLeast(0L)
            bubbleView.updateHoldProgress(elapsedMs.toFloat() / HOLD_TO_STOP_MS.toFloat())
            if (elapsedMs < HOLD_TO_STOP_MS) bubbleView.postOnAnimation(progressTick)
        }
        longPressThreshold = Runnable(::reachLongPressThreshold)
        stopAfterFullHold = Runnable(::stopAfterThreeSeconds)

        bubbleView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHoldTracking()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    holdStartedAtMs = event.downTime
                    activePointerId = event.getPointerId(event.actionIndex)
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    holdActive = true
                    longPressThresholdReached = false
                    stopTriggered = false
                    bubbleView.beginHoldProgress()
                    bubbleView.postOnAnimation(progressTick)
                    val elapsedAtDispatchMs =
                        (SystemClock.uptimeMillis() - holdStartedAtMs).coerceAtLeast(0L)
                    mainHandler.postDelayed(
                        longPressThreshold,
                        (ViewConfiguration.getLongPressTimeout().toLong() - elapsedAtDispatchMs)
                            .coerceAtLeast(0L),
                    )
                    mainHandler.postDelayed(
                        stopAfterFullHold,
                        (HOLD_TO_STOP_MS - elapsedAtDispatchMs).coerceAtLeast(0L),
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) {
                        moved = true
                        cancelHoldTracking()
                    } else {
                        val rawOffsetX = event.rawX - event.x
                        val rawOffsetY = event.rawY - event.y
                        val currentRawX = event.getX(pointerIndex) + rawOffsetX
                        val currentRawY = event.getY(pointerIndex) + rawOffsetY
                        val dx = currentRawX - downRawX
                        val dy = currentRawY - downRawY
                        if (!moved && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            moved = true
                            cancelHoldTracking()
                        }

                        if (moved && bubbleAdded) {
                            val safeArea = safeDisplayArea()
                            val minX = safeArea.left + edgeMargin
                            val maxX = max(minX, safeArea.right - bubbleSize - edgeMargin)
                            val minY = safeArea.top + edgeMargin
                            val maxY =
                                max(minY, safeArea.bottom - currentControlHeight() - edgeMargin)
                            bubbleParams.x = (startX + dx.toInt()).coerceIn(minX, maxX)
                            bubbleParams.y = (startY + dy.toInt()).coerceIn(minY, maxY)
                            try {
                                windowManager.updateViewLayout(controlRoot, bubbleParams)
                            } catch (_: Exception) {
                                // Ignore a race with service shutdown.
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Multi-touch must not complete a click, cancel, or three-second stop.
                    moved = true
                    cancelHoldTracking()
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    moved = true
                    cancelHoldTracking()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val releasedPointerId = event.getPointerId(event.actionIndex)
                    if (
                        activePointerId == MotionEvent.INVALID_POINTER_ID ||
                        releasedPointerId != activePointerId
                    ) {
                        moved = true
                    }
                    val heldForMs = (event.eventTime - holdStartedAtMs).coerceAtLeast(0L)
                    if (!moved && heldForMs >= HOLD_TO_STOP_MS) stopAfterThreeSeconds()
                    if (
                        !moved &&
                        !stopTriggered &&
                        heldForMs >= ViewConfiguration.getLongPressTimeout()
                    ) {
                        reachLongPressThreshold()
                    }
                    val shouldClick = !moved && !longPressThresholdReached && !stopTriggered
                    cancelHoldTracking()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (shouldClick) {
                        ScreenInteractionBridge.markBubbleClick(event.eventTime)
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelHoldTracking()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    moved = true
                    true
                }

                else -> false
            }
        }
    }

    private fun clampBubbleToSafeArea() {
        if (!bubbleAdded || destroyed) return
        val safeArea = safeDisplayArea()
        val minX = safeArea.left + edgeMargin
        val maxX = max(minX, safeArea.right - bubbleSize - edgeMargin)
        val minY = safeArea.top + edgeMargin
        val maxY = max(minY, safeArea.bottom - currentControlHeight() - edgeMargin)
        val newX = bubbleParams.x.coerceIn(minX, maxX)
        val newY = bubbleParams.y.coerceIn(minY, maxY)
        if (newX == bubbleParams.x && newY == bubbleParams.y) return

        bubbleParams.x = newX
        bubbleParams.y = newY
        try {
            windowManager.updateViewLayout(controlRoot, bubbleParams)
        } catch (_: Exception) {
            // Ignore a race with service shutdown or permission revocation.
        }
    }

    private fun safeDisplayArea(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            val bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        }

        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        return Rect(
            0,
            statusBar,
            metrics.widthPixels,
            max(statusBar, metrics.heightPixels - navigationBar),
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun systemDimension(name: String): Int {
        val identifier = appContext.resources.getIdentifier(name, "dimen", "android")
        return if (identifier == 0) 0 else appContext.resources.getDimensionPixelSize(identifier)
    }

    private fun showErrorInternal(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    private fun showTemporaryBubbleError() {
        if (!bubbleAdded || destroyed) return
        busy = false
        bubbleView.text = "!"
        bubbleView.background = bubbleBackground(BUBBLE_ERROR_COLOR)
        bubbleView.alpha = 1f
        mainHandler.removeCallbacks(resetBubbleStateRunnable)
        mainHandler.postDelayed(resetBubbleStateRunnable, ERROR_STATE_DURATION_MS)
    }

    private val resetBubbleStateRunnable = Runnable {
        if (!destroyed && bubbleAdded && !busy) {
            bubbleView.text = appContext.getString(R.string.screen_translation_bubble_label)
            bubbleView.background = bubbleBackground(BUBBLE_COLOR)
        }
    }

    private fun safeOverlayAlpha(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return DEFAULT_OVERLAY_ALPHA
        val inputManager = appContext.getSystemService(InputManager::class.java)
        return min(
            DEFAULT_OVERLAY_ALPHA,
            (inputManager.maximumObscuringOpacityForTouch - OVERLAY_ALPHA_MARGIN)
                .coerceAtLeast(MIN_OVERLAY_ALPHA),
        )
    }

    private fun bubbleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(110, 255, 255, 255))
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density + 0.5f).toInt()

    private fun currentControlHeight(): Int =
        if (appButtonVisible) controlHeight else bubbleSize

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private companion object {
        const val TAG = "VoiceBridgeScreenOverlay"
        const val SETTINGS_DESCRIPTION = "Open VoiceBridge"
        const val DEFAULT_OVERLAY_ALPHA = 0.79f
        const val MIN_OVERLAY_ALPHA = 0.2f
        const val OVERLAY_ALPHA_MARGIN = 0.01f
        const val ERROR_STATE_DURATION_MS = 3_500L
        const val HOLD_TO_STOP_MS = 3_000L
        const val IDLE_BUBBLE_DESCRIPTION =
            "Tap to translate the screen. Hold for 3 seconds to stop screen translation."
        const val BUSY_BUBBLE_DESCRIPTION =
            "Translation is running. Hold briefly to cancel, or for 3 seconds to stop."
        const val TRANSLATION_VISIBLE_BUBBLE_DESCRIPTION =
            "Tap to hide the translation. Tap again to translate the current screen."
        const val OVERLAY_PERMISSION_ERROR =
            "Could not show the RU button. Allow VoiceBridge to display over other apps."
        const val TRANSLATION_OVERLAY_ERROR =
            "Could not show the translation overlay. Check the display-over-apps permission."
        const val NO_TEXT_ERROR = "No readable text was found on this screen."
        const val NO_USEFUL_TRANSLATION_ERROR =
            "The local model did not return any usable translation."
        val BUBBLE_COLOR: Int = Color.rgb(42, 91, 211)
        val BUBBLE_BUSY_COLOR: Int = Color.rgb(91, 104, 132)
        val BUBBLE_ERROR_COLOR: Int = Color.rgb(181, 45, 55)
        val APP_BUTTON_COLOR: Int = Color.rgb(49, 87, 213)
    }
}

@SuppressLint("AppCompatCustomView")
private class HoldProgressTextView(context: Context) : TextView(context) {
    private val density = resources.displayMetrics.density
    private val ringStrokeWidth = max(2f, density * 2.5f)
    private val ringBounds = RectF()
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = ringStrokeWidth
    }
    private val ringProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 218, 74)
        style = Paint.Style.STROKE
        strokeWidth = ringStrokeWidth
        strokeCap = Paint.Cap.ROUND
    }
    private var holdProgressVisible = false
    private var holdProgress = 0f

    fun beginHoldProgress() {
        holdProgressVisible = true
        holdProgress = 0f
        invalidate()
    }

    fun updateHoldProgress(value: Float) {
        holdProgressVisible = true
        holdProgress = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun completeHoldProgress() = updateHoldProgress(1f)

    fun clearHoldProgress() {
        if (!holdProgressVisible && holdProgress == 0f) return
        holdProgressVisible = false
        holdProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!holdProgressVisible || width <= 0 || height <= 0) return

        val inset = ringStrokeWidth / 2f + density
        ringBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(ringBounds, -90f, 360f, false, ringTrackPaint)
        if (holdProgress > 0f) {
            canvas.drawArc(ringBounds, -90f, 360f * holdProgress, false, ringProgressPaint)
        }
    }

    override fun performClick(): Boolean = super.performClick()
}

internal class TranslationOverlayView(context: Context) : View(context) {
    private data class ScrollOffset(var x: Float = 0f, var y: Float = 0f)

    private data class WidthFittedLayout(
        val width: Int,
        val layout: StaticLayout,
    )

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(12, 19, 31)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density * 0.65f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val attributionText = context.getString(R.string.google_translate_attribution)
    private val attributionBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 12, 19, 31)
        style = Paint.Style.FILL
    }
    private val attributionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        textSize = 8f * scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val fullDisplayBounds = Rect()
    private val visibleDisplayFrame = Rect()
    private val viewLocationOnScreen = IntArray(2)
    private val labelRect = RectF()
    private val attributionRect = RectF()
    private var blocks: List<TranslationBlock> = emptyList()
    private val contentOffsets = HashMap<String, ScrollOffset>()
    private val pendingOffsets = HashMap<String, ScrollOffset>()
    private var scrollFrameScheduled = false

    private val applyPendingScroll = Runnable {
        scrollFrameScheduled = false
        if (blocks.isEmpty()) {
            pendingOffsets.clear()
            return@Runnable
        }
        pendingOffsets.forEach { (groupId, pending) ->
            val current = contentOffsets.getOrPut(groupId, ::ScrollOffset)
            current.x += pending.x
            current.y += pending.y
        }
        pendingOffsets.clear()
        invalidate()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setBlocks(value: List<TranslationBlock>): List<TranslationBlock> {
        removeCallbacks(applyPendingScroll)
        scrollFrameScheduled = false
        pendingOffsets.clear()
        contentOffsets.clear()
        val seen = HashSet<String>()
        blocks = value.asSequence()
            .map { block ->
                block.copy(
                    source = normalizeWhitespace(block.source),
                    translation = normalizeWhitespace(block.translation),
                )
            }
            .filter(::isUsefulTranslation)
            .filter { block ->
                val key = buildString {
                    append(comparisonKey(block.translation))
                    append('|')
                    append((block.x * 100f).roundToInt())
                    append('|')
                    append((block.y * 100f).roundToInt())
                    append('|')
                    append(block.scrollGroupId.orEmpty())
                }
                seen.add(key)
            }
            .sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            .take(MAX_LABEL_COUNT)
            .toList()
        invalidate()
        return blocks
    }

    fun hasBlocks(): Boolean = blocks.isNotEmpty()

    fun moveWithContent(scrollGroupId: String?, deltaX: Int, deltaY: Int): Boolean {
        val groupId = scrollGroupId ?: return false
        if (blocks.none { it.scrollGroupId == groupId }) return false

        val pending = pendingOffsets.getOrPut(groupId, ::ScrollOffset)
        pending.x -= deltaX.toFloat()
        pending.y -= deltaY.toFloat()
        if (!scrollFrameScheduled) {
            scrollFrameScheduled = true
            postOnAnimation(applyPendingScroll)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || blocks.isEmpty()) return

        updateFullDisplayBounds()
        getLocationOnScreen(viewLocationOnScreen)
        val sourceWidth = fullDisplayBounds.width().coerceAtLeast(1).toFloat()
        val sourceHeight = fullDisplayBounds.height().coerceAtLeast(1).toFloat()
        val horizontalPadding = dp(3)
        val verticalPadding = dp(1)
        val horizontalCover = dp(2).toFloat()
        val verticalCover = dp(1).toFloat()
        val radius = dp(3).toFloat()

        blocks.forEach { block ->
            val translated = block.translation.trim()
            if (translated.isEmpty()) return@forEach

            val normalizedLeft = block.x.coerceIn(0f, 1f)
            val normalizedTop = block.y.coerceIn(0f, 1f)
            val normalizedWidth = block.width.coerceIn(0f, 1f - normalizedLeft)
            val normalizedHeight = block.height.coerceIn(0f, 1f - normalizedTop)
            val contentOffset = block.scrollGroupId?.let(contentOffsets::get)

            val anchorLeft = fullDisplayBounds.left + normalizedLeft * sourceWidth -
                viewLocationOnScreen[0] + (contentOffset?.x ?: 0f)
            val anchorTop = fullDisplayBounds.top + normalizedTop * sourceHeight -
                viewLocationOnScreen[1] + (contentOffset?.y ?: 0f)
            val anchorWidth = max(dp(8).toFloat(), normalizedWidth * sourceWidth)
            val anchorHeight = max(dp(6).toFloat(), normalizedHeight * sourceHeight)
            if (anchorTop + anchorHeight < 0f || anchorTop > height.toFloat()) return@forEach

            val requestedLeft = anchorLeft - horizontalCover
            val requestedWidth = anchorWidth + horizontalCover * 2f
            val initialLabelWidth = requestedWidth
                .coerceIn(1f, width.toFloat())
                .let { ceil(it).toInt() }
            val targetTextHeight = max(
                dp(12),
                (anchorHeight - verticalPadding * 2f).roundToInt(),
            )
            val fitted = fitWithHorizontalExpansion(
                text = translated,
                initialLabelWidth = initialLabelWidth,
                maximumLabelWidth = width,
                horizontalPadding = horizontalPadding,
                targetHeight = targetTextHeight,
            )
            val labelWidth = fitted.width
            val layout = fitted.layout

            val labelHeight = max(
                (anchorHeight + verticalCover * 2f).roundToInt(),
                layout.height + verticalPadding * 2,
            )
            val left = expandedLabelLeft(
                requestedLeft = requestedLeft,
                requestedRight = anchorLeft + anchorWidth + horizontalCover,
                labelWidth = labelWidth,
                viewWidth = width,
            )
            val top = (anchorTop - verticalCover).coerceIn(
                0f,
                (height - labelHeight).coerceAtLeast(0).toFloat(),
            )
            labelRect.set(left, top, left + labelWidth, top + labelHeight)

            canvas.drawRoundRect(labelRect, radius, radius, backgroundPaint)
            canvas.drawRoundRect(labelRect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(labelRect.left + horizontalPadding, labelRect.top + verticalPadding)
            layout.draw(canvas)
            canvas.restore()
        }

        drawAttribution(canvas)
    }

    private fun drawAttribution(canvas: Canvas) {
        getWindowVisibleDisplayFrame(visibleDisplayFrame)
        val visibleRight = (visibleDisplayFrame.right - viewLocationOnScreen[0])
            .toFloat()
            .coerceIn(0f, width.toFloat())
        val visibleBottom = (visibleDisplayFrame.bottom - viewLocationOnScreen[1])
            .toFloat()
            .coerceIn(0f, height.toFloat())
        val horizontalPadding = dp(5).toFloat()
        val verticalPadding = dp(3).toFloat()
        val margin = dp(8).toFloat()
        val fontMetrics = attributionTextPaint.fontMetrics
        val textWidth = attributionTextPaint.measureText(attributionText)
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val tagWidth = textWidth + horizontalPadding * 2f
        val tagHeight = textHeight + verticalPadding * 2f
        val right = (visibleRight - margin).coerceAtLeast(tagWidth)
        val bottom = (visibleBottom - margin).coerceAtLeast(tagHeight)
        attributionRect.set(
            right - tagWidth,
            bottom - tagHeight,
            right,
            bottom,
        )
        val radius = dp(3).toFloat()
        canvas.drawRoundRect(
            attributionRect,
            radius,
            radius,
            attributionBackgroundPaint,
        )
        canvas.drawText(
            attributionText,
            attributionRect.left + horizontalPadding,
            attributionRect.top + verticalPadding - fontMetrics.ascent,
            attributionTextPaint,
        )
    }

    private fun fittedLayout(text: String, width: Int, targetHeight: Int): StaticLayout {
        var textSizeSp = MAX_TEXT_SIZE_SP
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = textSizeSp * scaledDensity
            layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            if (layout.height <= targetHeight || textSizeSp <= MIN_TEXT_SIZE_SP) return layout
            textSizeSp = (textSizeSp - TEXT_SIZE_STEP_SP).coerceAtLeast(MIN_TEXT_SIZE_SP)
        }
    }

    private fun fitWithHorizontalExpansion(
        text: String,
        initialLabelWidth: Int,
        maximumLabelWidth: Int,
        horizontalPadding: Int,
        targetHeight: Int,
    ): WidthFittedLayout {
        val initialWidth = initialLabelWidth.coerceIn(1, maximumLabelWidth.coerceAtLeast(1))
        fun layoutAt(labelWidth: Int): StaticLayout = fittedLayout(
            text = text,
            width = max(1, labelWidth - horizontalPadding * 2),
            targetHeight = targetHeight,
        )

        val initialLayout = layoutAt(initialWidth)
        if (initialLayout.height <= targetHeight || initialWidth >= maximumLabelWidth) {
            return WidthFittedLayout(initialWidth, initialLayout)
        }

        var low = initialWidth + 1
        var high = maximumLabelWidth
        var best: WidthFittedLayout? = null
        while (low <= high) {
            val candidateWidth = low + (high - low) / 2
            val candidateLayout = layoutAt(candidateWidth)
            if (candidateLayout.height <= targetHeight) {
                best = WidthFittedLayout(candidateWidth, candidateLayout)
                high = candidateWidth - 1
            } else {
                low = candidateWidth + 1
            }
        }
        return best ?: WidthFittedLayout(maximumLabelWidth, layoutAt(maximumLabelWidth))
    }

    private fun expandedLabelLeft(
        requestedLeft: Float,
        requestedRight: Float,
        labelWidth: Int,
        viewWidth: Int,
    ): Float {
        val availableWidth = viewWidth.coerceAtLeast(1).toFloat()
        val coveredLeft = requestedLeft.coerceIn(0f, availableWidth)
        val coveredRight = requestedRight.coerceIn(coveredLeft, availableWidth)
        val maximumLeft = (availableWidth - labelWidth).coerceAtLeast(0f)
        val minimumCoveringLeft = (coveredRight - labelWidth).coerceAtLeast(0f)
        val maximumCoveringLeft = min(coveredLeft, maximumLeft)
        val centered = (coveredLeft + coveredRight - labelWidth) / 2f
        return if (minimumCoveringLeft <= maximumCoveringLeft) {
            centered.coerceIn(minimumCoveringLeft, maximumCoveringLeft)
        } else {
            centered.coerceIn(0f, maximumLeft)
        }
    }

    private fun updateFullDisplayBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fullDisplayBounds.set(windowManager.maximumWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            fullDisplayBounds.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun isUsefulTranslation(block: TranslationBlock): Boolean {
        val source = block.source.trim()
        val translation = block.translation.trim()
        if (source.isEmpty() || translation.isEmpty()) return false
        if (source.none(Char::isLetter) || translation.none(Char::isLetter)) return false

        val sourceKey = comparisonKey(source)
        val translationKey = comparisonKey(translation)
        if (sourceKey.isEmpty() || translationKey.isEmpty()) return false
        return sourceKey != translationKey
    }

    private fun comparisonKey(value: String): String = buildString(value.length) {
        value.lowercase(Locale.ROOT).forEach { character ->
            if (character.isLetterOrDigit()) append(character)
        }
    }

    private fun normalizeWhitespace(value: String): String =
        WHITESPACE_REGEX.replace(value, " ").trim()

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val MAX_LABEL_COUNT = 500
        const val MAX_TEXT_SIZE_SP = 13.5f
        const val MIN_TEXT_SIZE_SP = 9f
        const val TEXT_SIZE_STEP_SP = 0.5f
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
