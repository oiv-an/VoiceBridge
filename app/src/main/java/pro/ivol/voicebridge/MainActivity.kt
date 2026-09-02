package pro.ivol.voicebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import pro.ivol.voicebridge.screen.ScreenInteractionBridge
import pro.ivol.voicebridge.screen.ScreenTranslationModelManager
import pro.ivol.voicebridge.screen.ScreenTranslationService
import pro.ivol.voicebridge.screen.ScreenTranslationServiceState
import pro.ivol.voicebridge.screen.isScreenTrackingAccessibilityConfigured
import pro.ivol.voicebridge.screen.isScreenTrackingAccessibilityEnabled
import pro.ivol.voicebridge.ui.VoiceBridgeScreen
import pro.ivol.voicebridge.ui.VoiceBridgeViewModel
import pro.ivol.voicebridge.ui.theme.VoiceBridgeTheme

class MainActivity : ComponentActivity() {
    private var openScreenTranslationSettingsRequest by mutableIntStateOf(0)
    private var returnToMainRequest by mutableIntStateOf(0)

    private val viewModel: VoiceBridgeViewModel by viewModels {
        VoiceBridgeViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeScreenTranslationSettingsIntent(intent)
        enableEdgeToEdge()

        setContent {
            VoiceBridgeTheme {
                VoiceBridgeRoute(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeScreenTranslationSettingsIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ScreenInteractionBridge.setHostAppVisible(true)
    }

    override fun onStop() {
        ScreenInteractionBridge.setHostAppVisible(false)
        super.onStop()
    }

    @Composable
    private fun VoiceBridgeRoute(viewModel: VoiceBridgeViewModel) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        var hasMicrophonePermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }
        var deniedBefore by rememberSaveable { mutableStateOf(false) }
        var hasScreenOverlayPermission by remember {
            mutableStateOf(Settings.canDrawOverlays(this))
        }
        var screenAccessibilityConfigured by remember {
            mutableStateOf(isScreenTrackingAccessibilityConfigured())
        }
        var screenAccessibilityConnected by remember {
            mutableStateOf(isScreenTrackingAccessibilityEnabled())
        }
        var screenTranslationState by remember {
            mutableStateOf(ScreenTranslationService.state)
        }
        var screenTranslationError by remember {
            mutableStateOf(ScreenTranslationService.lastErrorMessage)
        }
        var screenTranslationActionError by remember { mutableStateOf<String?>(null) }
        var screenTranslationModelsReady by remember { mutableStateOf<Boolean?>(null) }
        var screenTranslationModelsError by remember { mutableStateOf<String?>(null) }
        var screenModelCheckRequest by remember { mutableIntStateOf(0) }

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val permissionIsGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionIsGranted && !hasMicrophonePermission) {
                viewModel.onPermissionGranted()
            }
            hasMicrophonePermission = permissionIsGranted
            hasScreenOverlayPermission = Settings.canDrawOverlays(this)
            screenAccessibilityConfigured = isScreenTrackingAccessibilityConfigured()
            screenAccessibilityConnected = isScreenTrackingAccessibilityEnabled()
            screenTranslationState = ScreenTranslationService.state
            screenTranslationError = ScreenTranslationService.lastErrorMessage
            screenModelCheckRequest += 1
        }

        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            viewModel.onHostStopped()
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasMicrophonePermission = granted
            if (granted) {
                deniedBefore = false
                viewModel.onPermissionGranted()
            } else {
                val permanentlyDenied = deniedBefore &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    )
                deniedBefore = true
                viewModel.onPermissionDenied(permanently = permanentlyDenied)
            }
        }

        fun startScreenTranslation() {
            screenTranslationActionError = null
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    ScreenTranslationService.startIntent(this),
                )
            }.onSuccess {
                screenTranslationState = ScreenTranslationServiceState.STARTING
                screenTranslationError = null
            }.onFailure {
                screenTranslationState = ScreenTranslationServiceState.ERROR
                screenTranslationActionError =
                    "Android could not start the floating translation button."
            }
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            // Android keeps foreground-service controls available even when the
            // optional notification permission is declined.
            startScreenTranslation()
        }

        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    screenAccessibilityConnected = isScreenTrackingAccessibilityEnabled()
                    screenTranslationState = ScreenTranslationService.state
                    screenTranslationError = ScreenTranslationService.lastErrorMessage
                    delay(SCREEN_TRANSLATION_STATUS_REFRESH_MS)
                }
            }
        }

        LaunchedEffect(
            screenModelCheckRequest,
            uiState.isModelDownloading,
            uiState.isScreenModelDownloading,
            uiState.localModelInstalled,
        ) {
            if (uiState.isModelDownloading || uiState.isScreenModelDownloading) {
                screenTranslationModelsReady = null
                screenTranslationModelsError = null
            } else {
                screenTranslationModelsReady = null
                screenTranslationModelsError = null
                val modelCheck = withContext(Dispatchers.IO) {
                    runCatching {
                        ScreenTranslationModelManager(this@MainActivity)
                            .areCoreModelsDownloaded()
                    }
                }
                modelCheck.fold(
                    onSuccess = { ready -> screenTranslationModelsReady = ready },
                    onFailure = {
                        screenTranslationModelsError =
                            "Could not check the installed translation models."
                    },
                )
            }
        }

        VoiceBridgeScreen(
            state = uiState,
            hasMicrophonePermission = hasMicrophonePermission,
            onSourceLanguageSelected = viewModel::selectSourceLanguage,
            onTargetLanguageSelected = viewModel::selectTargetLanguage,
            onSwapLanguages = viewModel::swapLanguages,
            onCurrencyToggled = viewModel::toggleCurrency,
            onSpeechModeSelected = viewModel::selectSpeechRecognitionMode,
            onDownloadLocalModel = viewModel::downloadLocalModel,
            onDownloadScreenTranslationModels =
                viewModel::downloadOfflineModelsForScreenTranslation,
            onCancelLocalModelDownload = viewModel::cancelLocalModelDownload,
            onSpeakTranscript = viewModel::speakTranscript,
            onSpeakTranslation = viewModel::speakTranslation,
            onTranscriptChanged = viewModel::onTranscriptChanged,
            onEstimateCurrencies = viewModel::estimateCurrenciesForVnd,
            onPushToTalkPressed = { direction ->
                viewModel.onPushToTalkPressed(direction)
            },
            onPushToTalkReleased = { direction ->
                viewModel.onPushToTalkReleased(direction)
            },
            onRequestMicrophonePermission = {
                if (uiState.microphonePermissionPermanentlyDenied) {
                    openVoiceBridgeAppInfo()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            openSettingsRequest = openScreenTranslationSettingsRequest,
            returnToMainRequest = returnToMainRequest,
            hasScreenOverlayPermission = hasScreenOverlayPermission,
            screenAccessibilityConfigured = screenAccessibilityConfigured,
            screenAccessibilityConnected = screenAccessibilityConnected,
            screenTranslationModelsReady = screenTranslationModelsReady,
            screenTranslationModelsError = screenTranslationModelsError,
            screenTranslationState = screenTranslationState,
            screenTranslationError = screenTranslationActionError ?: screenTranslationError,
            onRequestScreenOverlayPermission = ::openScreenOverlaySettings,
            onRequestScreenAccessibility = ::openScreenAccessibilitySettings,
            showScreenRestrictedSettingsHint =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            onOpenVoiceBridgeAppInfo = ::openVoiceBridgeAppInfo,
            onRetryScreenTranslationModelCheck = {
                screenTranslationModelsReady = null
                screenTranslationModelsError = null
                screenModelCheckRequest += 1
            },
            onStartScreenTranslation = {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startScreenTranslation()
                }
            },
            onStopScreenTranslation = {
                screenTranslationActionError = null
                runCatching {
                    startService(ScreenTranslationService.stopIntent(this))
                }.onFailure {
                    screenTranslationActionError =
                        "Android could not stop the floating translation button."
                }
            },
        )
    }

    private fun consumeScreenTranslationSettingsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SCREEN_TRANSLATION_SETTINGS, false) == true) {
            openScreenTranslationSettingsRequest += 1
            intent.removeExtra(EXTRA_OPEN_SCREEN_TRANSLATION_SETTINGS)
        }
        if (intent?.getBooleanExtra(EXTRA_RETURN_TO_MAIN, false) == true) {
            returnToMainRequest += 1
            intent.removeExtra(EXTRA_RETURN_TO_MAIN)
        }
    }

    private fun openScreenOverlaySettings() {
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_SETTINGS),
        )
        candidates.firstOrNull { candidate ->
            candidate.resolveActivity(packageManager) != null
        }?.let(::startActivity)
    }

    private fun openScreenAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .takeIf { it.resolveActivity(packageManager) != null }
            ?: Intent(Settings.ACTION_SETTINGS)
        startActivity(intent)
    }

    private fun openVoiceBridgeAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri(),
            ),
        )
    }

    companion object {
        const val EXTRA_OPEN_SCREEN_TRANSLATION_SETTINGS =
            "pro.ivol.voicebridge.extra.OPEN_SCREEN_TRANSLATION_SETTINGS"
        const val EXTRA_RETURN_TO_MAIN =
            "pro.ivol.voicebridge.extra.RETURN_TO_MAIN"
        private const val SCREEN_TRANSLATION_STATUS_REFRESH_MS = 500L
    }
}
