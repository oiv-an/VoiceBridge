package pro.ivol.voicebridge.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import pro.ivol.voicebridge.MainActivity
import pro.ivol.voicebridge.R

enum class ScreenTranslationServiceState {
    STOPPED,
    STOPPING,
    STARTING,
    READY,
    TRANSLATING,
    SHOWING,
    ERROR,
}

/**
 * Foreground coordinator for fully local screen-text translation.
 *
 * The service never captures an image. It receives one immutable text snapshot from
 * [ScreenTrackingAccessibilityService] after an explicit bubble tap, checks the already
 * installed ML Kit models, translates locally, and renders the result through an overlay.
 */
class ScreenTranslationService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionStarted = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val requestSequence = AtomicLong(0L)
    private val activeRequestId = AtomicLong(NO_ACTIVE_REQUEST_ID)
    private val requestStartedAtMs = AtomicLong(0L)
    private val activeClientRequestToken = AtomicLong(NO_ACTIVE_REQUEST_ID)
    private val screenGeneration = AtomicLong(0L)

    private lateinit var translationExecutor: ExecutorService
    private lateinit var localTranslationClient: LocalScreenTranslationClient
    private var overlayController: OverlayController? = null
    private var trackingDisconnectedAtMs = 0L

    private val screenInteractionListener = object : ScreenInteractionListener {
        override fun onContentScrolled(scrollGroupId: String?, deltaX: Int, deltaY: Int) {
            runOnMain {
                if (cleanupStarted.get()) return@runOnMain
                val requestCancelled = invalidateActiveRequest()
                if (requestCancelled) {
                    overlayController?.apply {
                        restoreBubble()
                        setBusy(false)
                    }
                    clearTranslationsAndFingerprint()
                } else {
                    overlayController?.moveTranslationsWithContent(scrollGroupId, deltaX, deltaY)
                }
            }
        }

        override fun onScreenChanged() {
            runOnMain {
                if (cleanupStarted.get()) return@runOnMain
                val requestCancelled = invalidateActiveRequest()
                if (requestCancelled) {
                    overlayController?.apply {
                        restoreBubble()
                        setBusy(false)
                    }
                }
                clearTranslationsAndFingerprint()
            }
        }

        override fun onHostAppVisibilityChanged(visible: Boolean) {
            runOnMain {
                if (!cleanupStarted.get()) {
                    overlayController?.setAppButtonVisible(!visible)
                }
            }
        }
    }

    private val capabilityCheck = object : Runnable {
        override fun run() {
            if (cleanupStarted.get()) return
            if (!Settings.canDrawOverlays(this@ScreenTranslationService)) {
                failSession("Display-over-other-apps permission was revoked.")
                return
            }
            if (!isScreenTrackingAccessibilityConfigured()) {
                failSession("Screen text access was disabled in Accessibility settings.")
                return
            }
            if (!ScreenInteractionBridge.isAccessibilityServiceConnected()) {
                val nowMs = SystemClock.elapsedRealtime()
                if (trackingDisconnectedAtMs == 0L) {
                    trackingDisconnectedAtMs = nowMs
                    val requestCancelled = invalidateActiveRequest()
                    if (requestCancelled) {
                        overlayController?.apply {
                            restoreBubble()
                            setBusy(false)
                        }
                    }
                    clearTranslationsAndFingerprint()
                } else if (nowMs - trackingDisconnectedAtMs >= TRACKING_RECONNECT_GRACE_MS) {
                    failSession("The screen text accessibility service did not reconnect.")
                    return
                }
            } else {
                trackingDisconnectedAtMs = 0L
            }
            mainHandler.postDelayed(this, CAPABILITY_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        translationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "local-screen-translation").apply { isDaemon = true }
        }
        localTranslationClient = LocalScreenTranslationClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val normalStopWasPending = state == ScreenTranslationServiceState.STOPPING
        stopSession(stopService = false)
        if (normalStopWasPending) state = ScreenTranslationServiceState.STOPPED
        super.onDestroy()
    }

    private fun startSession() {
        if (!sessionStarted.compareAndSet(false, true)) return
        state = ScreenTranslationServiceState.STARTING
        lastErrorMessage = null

        // A service started with startForegroundService must become foreground before
        // permission/model checks or any potentially slow ML Kit initialization.
        try {
            startTranslationForeground()
        } catch (error: Throwable) {
            failSession("Could not start the screen translation notification.")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            failSession("Allow VoiceBridge to display over other apps.")
            return
        }
        if (!isScreenTrackingAccessibilityConfigured()) {
            failSession("Enable the VoiceBridge screen translation accessibility service.")
            return
        }

        try {
            translationExecutor.execute {
                val missingModels = try {
                    localTranslationClient.missingCoreModelNames()
                } catch (error: Throwable) {
                    runOnMain {
                        failSession(
                            error.message?.takeIf(String::isNotBlank)
                                ?: "Could not check the offline translation models.",
                        )
                    }
                    return@execute
                }

                runOnMain {
                    if (cleanupStarted.get()) return@runOnMain
                    if (missingModels.isNotEmpty()) {
                        failSession(
                            "Missing offline model${if (missingModels.size == 1) "" else "s"}: " +
                                missingModels.joinToString() +
                                ". Open VoiceBridge settings and download the offline models.",
                        )
                        return@runOnMain
                    }
                    finishStartingSession()
                }
            }
        } catch (error: Throwable) {
            failSession("Could not check the offline translation models.")
        }
    }

    private fun finishStartingSession() {
        if (cleanupStarted.get()) return
        try {
            val controller = OverlayController(
                context = this,
                onTranslate = ::requestTranslation,
                onCancelTranslation = ::cancelTranslationFromBubbleHold,
                onStop = ::stopSessionFromBubbleHold,
                onOpenSettings = ::openSettingsFromOverlay,
                onTranslationsCleared = ::onOverlayTranslationsCleared,
                onTranslationsDisplayed = ::onOverlayTranslationsDisplayed,
            )
            controller.setAppButtonVisible(!ScreenInteractionBridge.isHostAppVisible())
            overlayController = controller
            check(controller.showBubble()) { "Unable to show the translation control." }
            ScreenInteractionBridge.attach(screenInteractionListener)
            state = ScreenTranslationServiceState.READY
            mainHandler.postDelayed(capabilityCheck, CAPABILITY_CHECK_INTERVAL_MS)
        } catch (error: Throwable) {
            failSession("Could not display the screen translation control.")
        }
    }

    private fun requestTranslation() {
        runOnMain {
            if (cleanupStarted.get()) return@runOnMain
            if (!Settings.canDrawOverlays(this)) {
                failSession("Display-over-other-apps permission was revoked.")
                return@runOnMain
            }
            if (!isScreenTrackingAccessibilityConfigured()) {
                failSession("Screen text access was disabled in Accessibility settings.")
                return@runOnMain
            }
            if (!ScreenInteractionBridge.isAccessibilityServiceConnected()) {
                showRequestError(
                    "The screen text service is reconnecting. Try again in a few seconds.",
                )
                return@runOnMain
            }

            val requestId = requestSequence.incrementAndGet()
            if (!activeRequestId.compareAndSet(NO_ACTIVE_REQUEST_ID, requestId)) return@runOnMain
            val clientRequestToken = localTranslationClient.beginRequest()
            activeClientRequestToken.set(clientRequestToken)

            lastErrorMessage = null
            requestStartedAtMs.set(SystemClock.elapsedRealtime())
            clearTranslationsAndFingerprint()
            state = ScreenTranslationServiceState.TRANSLATING
            Log.i(TAG, "screen_translation_started request=$requestId")
            overlayController?.apply {
                setBusy(true)
                hideForCapture()
            }

            mainHandler.postDelayed(
                { readAndTranslateVisibleText(requestId) },
                OVERLAY_HIDE_DELAY_MS,
            )
            mainHandler.postDelayed(
                { timeoutTranslationRequest(requestId) },
                TOTAL_REQUEST_TIMEOUT_MS,
            )
        }
    }

    private fun readAndTranslateVisibleText(requestId: Long) {
        if (cleanupStarted.get() || activeRequestId.get() != requestId) return
        try {
            translationExecutor.execute {
                captureAndTranslateVisibleText(requestId)
            }
        } catch (error: Throwable) {
            finishRequestWithError(
                requestId = requestId,
                message = "Could not start local screen translation.",
                errorCode = RequestErrorCode.REQUEST_DISPATCH_FAILED,
            )
        }
    }

    /** Accessibility traversal and ML Kit inference share one bounded worker request. */
    private fun captureAndTranslateVisibleText(requestId: Long) {
        if (cleanupStarted.get() || activeRequestId.get() != requestId) return

        val requestStartedAt = requestStartedAtMs.get()
        if (requestStartedAt <= 0L) return
        val requestDeadlineAtMs = requestStartedAt + TOTAL_REQUEST_TIMEOUT_MS
        val requestGeneration = screenGeneration.get()
        if (SystemClock.elapsedRealtime() >= requestDeadlineAtMs) {
            timeoutTranslationRequest(requestId)
            return
        }

        try {
            val snapshotDeadlineAtMs = minOf(
                requestDeadlineAtMs,
                SystemClock.elapsedRealtime() + SNAPSHOT_CAPTURE_TIMEOUT_MS,
            )
            val snapshot = try {
                ScreenInteractionBridge.captureVisibleTextSnapshot(snapshotDeadlineAtMs)
            } finally {
                mainHandler.post {
                    if (!cleanupStarted.get()) overlayController?.restoreBubble()
                }
            }
            val generationAfterSnapshot = screenGeneration.get()

            if (snapshot == null) {
                if (SystemClock.elapsedRealtime() >= requestDeadlineAtMs) {
                    timeoutTranslationRequest(requestId)
                } else {
                    val hostAppIsVisible =
                        ScreenInteractionBridge.activeWindowPackageName() == packageName
                    val message = if (hostAppIsVisible) {
                        "Open another app, then tap RU to translate its text."
                    } else {
                        "Android did not provide accessible text for this screen within 2 seconds."
                    }
                    finishRequestWithError(
                        requestId = requestId,
                        message = message,
                        errorCode = if (hostAppIsVisible) {
                            RequestErrorCode.HOST_APP_VISIBLE
                        } else {
                            RequestErrorCode.TEXT_CAPTURE_UNAVAILABLE
                        },
                    )
                }
                return
            }
            if (
                requestGeneration != generationAfterSnapshot ||
                activeRequestId.get() != requestId
            ) {
                finishRequestAfterScreenChange(requestId)
                return
            }

            val elements = snapshot.nodes.mapIndexed { index, node ->
                ScreenTextElement(
                    id = "t${index + 1}",
                    source = node.text,
                    bounds = ScreenTextBounds(
                        x = node.x,
                        y = node.y,
                        width = node.width,
                        height = node.height,
                    ),
                    scrollGroupId = node.scrollGroupId,
                )
            }
            if (elements.isEmpty()) {
                finishRequestWithError(
                    requestId = requestId,
                    message = "Android did not find accessible text on this screen.",
                    errorCode = RequestErrorCode.NO_ACCESSIBLE_TEXT,
                )
                return
            }
            if (
                requestGeneration != screenGeneration.get() ||
                activeRequestId.get() != requestId
            ) {
                finishRequestAfterScreenChange(requestId)
                return
            }

            val result = localTranslationClient.translate(
                elements = elements,
                fallbackLanguageCode = DEFAULT_FALLBACK_LANGUAGE_CODE,
                deadlineElapsedRealtimeMs = requestDeadlineAtMs,
                requestToken = activeClientRequestToken.get(),
            )
            mainHandler.post {
                if (cleanupStarted.get()) return@post
                if (requestGeneration != screenGeneration.get()) {
                    finishRequestAfterScreenChange(requestId)
                    return@post
                }
                if (!finishActiveRequest(requestId)) return@post
                requestStartedAtMs.set(0L)
                activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
                overlayController?.apply {
                    restoreBubble()
                    setBusy(false)
                }
                if (result.blocks.isEmpty()) {
                    clearTranslationsAndFingerprint()
                    showRequestError("There is no text on screen that needs translation.")
                } else {
                    val controller = overlayController
                    if (controller == null) {
                        clearTranslationsAndFingerprint()
                        showRequestError("The translation overlay is unavailable.")
                    } else {
                        Log.i(
                            TAG,
                            "screen_translation_finished request=$requestId blocks=${result.blocks.size}",
                        )
                        controller.showTranslations(result.blocks)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is a normal outcome. A stale generation/request owns no UI.
            if (
                activeRequestId.get() == requestId &&
                requestGeneration == screenGeneration.get()
            ) {
                finishRequestWithError(
                    requestId = requestId,
                    message = "Screen translation was cancelled.",
                    errorCode = RequestErrorCode.CANCELLED,
                )
            }
        } catch (error: Throwable) {
            if (requestGeneration != screenGeneration.get()) {
                mainHandler.post { finishRequestAfterScreenChange(requestId) }
            } else {
                finishRequestWithError(
                    requestId = requestId,
                    message = error.message?.takeIf(String::isNotBlank)
                        ?: "Local screen translation is temporarily unavailable.",
                    errorCode = error.requestErrorCode(),
                )
            }
        }
    }

    private fun finishRequestAfterScreenChange(requestId: Long) {
        runOnMain {
            if (cleanupStarted.get() || !finishActiveRequest(requestId)) return@runOnMain
            requestStartedAtMs.set(0L)
            activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
            overlayController?.apply {
                restoreBubble()
                setBusy(false)
            }
            clearTranslationsAndFingerprint()
        }
    }

    private fun finishRequestWithError(
        requestId: Long,
        message: String,
        errorCode: RequestErrorCode,
    ) {
        runOnMain {
            if (cleanupStarted.get() || !finishActiveRequest(requestId)) return@runOnMain
            requestStartedAtMs.set(0L)
            activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
            overlayController?.apply {
                restoreBubble()
                setBusy(false)
            }
            clearTranslationsAndFingerprint()
            Log.w(TAG, "screen_translation_failed request=$requestId code=${errorCode.logValue}")
            showRequestError(message)
        }
    }

    private fun Throwable.requestErrorCode(): RequestErrorCode = when (this) {
        is ScreenTranslationTimeoutException -> RequestErrorCode.TIMEOUT
        is ScreenTranslationModelException -> RequestErrorCode.MODEL_UNAVAILABLE
        is LocalScreenTranslationException -> RequestErrorCode.LOCAL_TRANSLATION_FAILED
        else -> RequestErrorCode.UNEXPECTED_FAILURE
    }

    private fun timeoutTranslationRequest(requestId: Long) {
        runOnMain {
            if (cleanupStarted.get()) return@runOnMain
            if (!activeRequestId.compareAndSet(requestId, NO_ACTIVE_REQUEST_ID)) return@runOnMain

            screenGeneration.incrementAndGet()
            requestStartedAtMs.set(0L)
            activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
            if (::localTranslationClient.isInitialized) {
                localTranslationClient.cancelActiveRequest()
            }
            overlayController?.apply {
                restoreBubble()
                setBusy(false)
            }
            clearTranslationsAndFingerprint()
            showRequestError("Screen translation did not finish within 25 seconds.")
        }
    }

    private fun invalidateActiveRequest(): Boolean {
        screenGeneration.incrementAndGet()
        val requestId = activeRequestId.getAndSet(NO_ACTIVE_REQUEST_ID)
        if (requestId == NO_ACTIVE_REQUEST_ID) return false

        requestStartedAtMs.set(0L)
        activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
        if (::localTranslationClient.isInitialized) {
            localTranslationClient.cancelActiveRequest()
        }
        return true
    }

    private fun finishActiveRequest(requestId: Long): Boolean =
        activeRequestId.compareAndSet(requestId, NO_ACTIVE_REQUEST_ID)

    private fun cancelTranslationFromBubbleHold() {
        if (cleanupStarted.get() || !invalidateActiveRequest()) return
        overlayController?.apply {
            restoreBubble()
            setBusy(false)
        }
        clearTranslationsAndFingerprint()
    }

    private fun stopSessionFromBubbleHold() {
        stopSession()
    }

    private fun openSettingsFromOverlay() {
        if (cleanupStarted.get()) return
        invalidateActiveRequest()
        overlayController?.apply {
            restoreBubble()
            setBusy(false)
        }
        clearTranslationsAndFingerprint()

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_RETURN_TO_MAIN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            SETTINGS_REQUEST_CODE,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            pendingIntent.send()
        } catch (_: PendingIntent.CanceledException) {
            runCatching { startActivity(activityIntent) }
        }
    }

    private fun onOverlayTranslationsCleared() {
        ScreenInteractionBridge.forgetProtectedContentFingerprint()
        returnLiveSessionToReady()
    }

    private fun onOverlayTranslationsDisplayed(acceptedBlocks: List<TranslationBlock>) {
        if (acceptedBlocks.isEmpty()) {
            ScreenInteractionBridge.forgetProtectedContentFingerprint()
            returnLiveSessionToReady()
            return
        }
        if (
            cleanupStarted.get() ||
            !sessionStarted.get() ||
            state != ScreenTranslationServiceState.TRANSLATING
        ) return

        ScreenInteractionBridge.rememberTranslatedSourceTexts(
            acceptedBlocks.map(TranslationBlock::source),
        )
        state = ScreenTranslationServiceState.SHOWING
    }

    private fun clearTranslationsAndFingerprint() {
        ScreenInteractionBridge.forgetProtectedContentFingerprint()
        overlayController?.clearTranslations()
        returnLiveSessionToReady()
    }

    private fun returnLiveSessionToReady() {
        if (
            sessionStarted.get() &&
            !cleanupStarted.get() &&
            (
                state == ScreenTranslationServiceState.TRANSLATING ||
                    state == ScreenTranslationServiceState.SHOWING
                )
        ) state = ScreenTranslationServiceState.READY
    }

    private fun showRequestError(message: String) {
        lastErrorMessage = message
        if (!cleanupStarted.get()) state = ScreenTranslationServiceState.READY
        overlayController?.showError(message)
    }

    private fun stopSession(
        stopService: Boolean = true,
        finalState: ScreenTranslationServiceState = ScreenTranslationServiceState.STOPPED,
    ) {
        if (!cleanupStarted.compareAndSet(false, true)) {
            if (stopService) stopSelf()
            return
        }

        state = if (
            stopService && finalState == ScreenTranslationServiceState.STOPPED
        ) {
            ScreenTranslationServiceState.STOPPING
        } else {
            finalState
        }
        ScreenInteractionBridge.detach(screenInteractionListener)
        activeRequestId.set(NO_ACTIVE_REQUEST_ID)
        screenGeneration.incrementAndGet()
        requestStartedAtMs.set(0L)
        activeClientRequestToken.set(NO_ACTIVE_REQUEST_ID)
        mainHandler.removeCallbacksAndMessages(null)
        if (::localTranslationClient.isInitialized) {
            localTranslationClient.cancelActiveRequest()
        }
        clearTranslationsAndFingerprint()
        if (::localTranslationClient.isInitialized) {
            runCatching(localTranslationClient::close)
        }
        if (::translationExecutor.isInitialized) translationExecutor.shutdownNow()

        overlayController?.runCatching { destroy() }
        overlayController = null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        if (stopService) stopSelf()
    }

    private fun failSession(message: String) {
        lastErrorMessage = message
        clearTranslationsAndFingerprint()
        stopSession(finalState = ScreenTranslationServiceState.ERROR)
    }

    private fun startTranslationForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val settingsPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_SETTINGS_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_SCREEN_TRANSLATION_SETTINGS, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_translation_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.screen_translation_notification_running))
            .setContentIntent(settingsPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.screen_translation_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.screen_translation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(
                    R.string.screen_translation_notification_channel_description,
                )
                setShowBadge(false)
            },
        )
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private enum class RequestErrorCode(val logValue: String) {
        REQUEST_DISPATCH_FAILED("request_dispatch_failed"),
        HOST_APP_VISIBLE("host_app_visible"),
        TEXT_CAPTURE_UNAVAILABLE("text_capture_unavailable"),
        NO_ACCESSIBLE_TEXT("no_accessible_text"),
        CANCELLED("cancelled"),
        TIMEOUT("timeout"),
        MODEL_UNAVAILABLE("model_unavailable"),
        LOCAL_TRANSLATION_FAILED("local_translation_failed"),
        UNEXPECTED_FAILURE("unexpected_failure"),
    }

    companion object {
        @Volatile
        var state: ScreenTranslationServiceState = ScreenTranslationServiceState.STOPPED
            private set

        @Volatile
        var lastErrorMessage: String? = null
            private set

        val isRunning: Boolean
            get() = state == ScreenTranslationServiceState.STOPPING ||
                state == ScreenTranslationServiceState.STARTING ||
                state == ScreenTranslationServiceState.READY ||
                state == ScreenTranslationServiceState.TRANSLATING ||
                state == ScreenTranslationServiceState.SHOWING

        const val ACTION_START =
            "pro.ivol.voicebridge.action.START_SCREEN_TRANSLATION"
        const val ACTION_STOP =
            "pro.ivol.voicebridge.action.STOP_SCREEN_TRANSLATION"
        const val EXTRA_OPEN_SCREEN_TRANSLATION_SETTINGS =
            "pro.ivol.voicebridge.extra.OPEN_SCREEN_TRANSLATION_SETTINGS"

        fun startIntent(context: Context): Intent =
            Intent(context, ScreenTranslationService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenTranslationService::class.java).setAction(ACTION_STOP)

        private const val DEFAULT_FALLBACK_LANGUAGE_CODE = "vi"
        private const val NO_ACTIVE_REQUEST_ID = 0L
        private const val NOTIFICATION_CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 2001
        private const val STOP_REQUEST_CODE = 2002
        private const val SETTINGS_REQUEST_CODE = 2003
        private const val TAG = "VoiceBridgeScreenTranslation"
        private const val NOTIFICATION_SETTINGS_REQUEST_CODE = 2004
        private const val OVERLAY_HIDE_DELAY_MS = 80L
        private const val SNAPSHOT_CAPTURE_TIMEOUT_MS = 2_000L
        private const val CAPABILITY_CHECK_INTERVAL_MS = 1_500L
        private const val TRACKING_RECONNECT_GRACE_MS = 8_000L
        private const val TOTAL_REQUEST_TIMEOUT_MS = 25_000L
    }
}
