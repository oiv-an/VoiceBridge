package pro.ivol.voicebridge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.ConversionCurrency
import pro.ivol.voicebridge.domain.CurrencySelection
import pro.ivol.voicebridge.domain.CurrencySelectionStore
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller
import pro.ivol.voicebridge.domain.LanguagePair
import pro.ivol.voicebridge.domain.LanguagePairStore
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent
import pro.ivol.voicebridge.domain.SpeechModeController
import pro.ivol.voicebridge.domain.SpeechRecognitionMode
import pro.ivol.voicebridge.domain.TextSpeaker
import pro.ivol.voicebridge.domain.TextSpeakerEvent
import pro.ivol.voicebridge.domain.TranslationEngine
import pro.ivol.voicebridge.domain.TranslationRequest
import pro.ivol.voicebridge.exchange.AndroidCbrVndRubRateProvider
import pro.ivol.voicebridge.exchange.VndRubRate
import pro.ivol.voicebridge.exchange.VndRubRateProvider
import pro.ivol.voicebridge.speech.SwitchableSpeechEngine
import pro.ivol.voicebridge.speech.AndroidTextSpeaker
import pro.ivol.voicebridge.speech.CombinedLocalModelInstaller
import pro.ivol.voicebridge.screen.ScreenTranslationModelInstaller
import pro.ivol.voicebridge.preferences.SharedPreferencesLanguagePairStore
import pro.ivol.voicebridge.preferences.SharedPreferencesCurrencySelectionStore
import pro.ivol.voicebridge.translation.GoogleMlKitTranslationEngine
import pro.ivol.voicebridge.translation.UniversalVndAmountParser
import java.math.BigDecimal

class VoiceBridgeViewModel(
    private val speechEngine: SpeechEngine,
    private val translationEngine: TranslationEngine,
    private val backTranslationEngine: TranslationEngine? = null,
    private val localModelInstaller: LocalModelInstaller? = null,
    private val screenTranslationModelInstaller: LocalModelInstaller? = null,
    private val textSpeaker: TextSpeaker? = null,
    private val languagePairStore: LanguagePairStore? = null,
    private val currencySelectionStore: CurrencySelectionStore? = null,
    private val vndRubRateProvider: VndRubRateProvider? = null,
) : ViewModel() {
    private val speechModeController = speechEngine as? SpeechModeController
    private val initialLanguagePair = runCatching { languagePairStore?.load() }
        .getOrNull()
        ?: LanguagePair()
    private val initialCurrencySelection = runCatching { currencySelectionStore?.load() }
        .getOrNull()
        ?: CurrencySelection()
    private val initialLocalBundleInstalled = localBundleInstalled()

    private val mutableUiState = MutableStateFlow(
        VoiceBridgeUiState(
            speechBackend = speechEngine.displayName,
            speechRecognitionMode =
                speechModeController?.selectedMode ?: SpeechRecognitionMode.ONLINE,
            localModelInstalled = initialLocalBundleInstalled,
            localSpeechAvailable =
                initialLocalBundleInstalled && speechModeController?.localModeAvailable == true,
            translationBackend = translationEngine.displayName,
            sourceLanguage = initialLanguagePair.sourceLanguage,
            targetLanguage = initialLanguagePair.targetLanguage,
            configuredSourceLanguage = initialLanguagePair.sourceLanguage,
            configuredTargetLanguage = initialLanguagePair.targetLanguage,
            selectedCurrencies = initialCurrencySelection.currencies,
        ),
    )
    val uiState: StateFlow<VoiceBridgeUiState> = mutableUiState.asStateFlow()

    private var translationJob: Job? = null
    private var currencyConversionJob: Job? = null
    private var modelInstallJob: Job? = null
    private var activeModelInstaller: LocalModelInstaller? = null
    private var activeModelInstallKind: ModelInstallKind? = null
    private var activateLocalAfterModelInstall = false
    private var translationRevision = 0L
    private var currencyConversionRevision = 0L

    init {
        speechEngine.setListener(::onSpeechEvent)
        textSpeaker?.setListener(::onTextSpeakerEvent)
    }

    fun downloadLocalModel() {
        startLocalModelDownload(
            kind = ModelInstallKind.FULL_BUNDLE,
            installer = localModelInstaller,
        )
    }

    fun downloadOfflineModelsForScreenTranslation() {
        startLocalModelDownload(
            kind = ModelInstallKind.SCREEN_TRANSLATION,
            installer = screenTranslationModelInstaller,
        )
    }

    private fun startLocalModelDownload(
        kind: ModelInstallKind,
        installer: LocalModelInstaller?,
    ) {
        if (modelInstallJob?.isActive == true) return

        val availableInstaller = installer ?: run {
            dispatchModelDownloadUnavailable(kind)
            return
        }

        if (kind == ModelInstallKind.FULL_BUNDLE) {
            activateLocalAfterModelInstall = true
            dispatch(VoiceBridgeAction.ModelDownloadStarted)
        } else {
            dispatch(VoiceBridgeAction.ScreenModelDownloadStarted)
        }

        val installJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                availableInstaller.install().collect { event ->
                    onModelInstallEvent(event, kind)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                dispatchModelDownloadFailure(
                    kind = kind,
                    message = throwable.message ?: "Could not download the offline models.",
                )
            } finally {
                if (modelInstallJob === currentCoroutineContext()[Job]) {
                    modelInstallJob = null
                    activeModelInstaller = null
                    activeModelInstallKind = null
                }
            }
        }
        modelInstallJob = installJob
        activeModelInstaller = availableInstaller
        activeModelInstallKind = kind
        installJob.start()
    }

    fun cancelLocalModelDownload() {
        if (modelInstallJob?.isActive != true) return
        val activeJob = modelInstallJob ?: return
        val installKind = activeModelInstallKind ?: return
        if (installKind == ModelInstallKind.FULL_BUNDLE) {
            activateLocalAfterModelInstall = false
        }
        runCatching { activeModelInstaller?.cancel() }
        activeJob.cancel()
        if (modelInstallJob === activeJob) {
            modelInstallJob = null
            activeModelInstaller = null
            activeModelInstallKind = null
        }
        when (installKind) {
            ModelInstallKind.FULL_BUNDLE -> dispatch(
                VoiceBridgeAction.ModelDownloadCancelled(
                    localModelInstalled = localBundleInstalled(),
                    localModeAvailable = localBundleAvailable(),
                ),
            )

            ModelInstallKind.SCREEN_TRANSLATION ->
                dispatch(VoiceBridgeAction.ScreenModelDownloadCancelled)
        }
    }

    fun speakTranscript() {
        val state = mutableUiState.value
        speakText(state.transcript, state.sourceLanguage)
    }

    fun speakTranslation() {
        val state = mutableUiState.value
        speakText(state.translation, state.targetLanguage)
    }

    /** Immediate calculator estimate using one saved/bundled CBR snapshot and the same 5% buffer. */
    fun estimateCurrenciesForVnd(amountVnd: Long): QuickCurrencyEstimateUi? {
        if (amountVnd < 0L) return null
        val provider = vndRubRateProvider ?: return null
        val rate = runCatching(provider::cachedRate).getOrNull() ?: return null
        return runCatching { bufferedCurrencyEstimate(amountVnd, rate) }.getOrNull()
    }

    fun toggleCurrency(currency: ConversionCurrency) {
        if (recognitionIsActive()) return
        val current = CurrencySelection(mutableUiState.value.selectedCurrencies)
        val updated = current.toggle(currency)
        if (updated == current) return
        dispatch(VoiceBridgeAction.CurrencySelectionChanged(updated.currencies))
        runCatching { currencySelectionStore?.save(updated) }
    }

    fun selectSourceLanguage(language: AppLanguage) {
        if (recognitionIsActive()) return
        cancelTranslation()
        cancelCurrencyConversion()
        dispatch(VoiceBridgeAction.SourceLanguageSelected(language))
        persistConfiguredLanguagePair()
        refreshCurrencyConversion(delayMillis = 0)
    }

    fun selectTargetLanguage(language: AppLanguage) {
        if (recognitionIsActive()) return
        cancelTranslation()
        cancelCurrencyConversion()
        dispatch(VoiceBridgeAction.TargetLanguageSelected(language))
        persistConfiguredLanguagePair()
        translateCurrentText(delayMillis = 0)
        refreshCurrencyConversion(delayMillis = 0)
    }

    fun swapLanguages() {
        if (recognitionIsActive()) return
        cancelTranslation()
        cancelCurrencyConversion()
        dispatch(VoiceBridgeAction.LanguagesSwapped)
        persistConfiguredLanguagePair()
        refreshCurrencyConversion(delayMillis = 0)
    }

    fun selectSpeechRecognitionMode(mode: SpeechRecognitionMode) {
        if (recognitionIsActive()) return

        if (mode == SpeechRecognitionMode.ONLINE) {
            // A stale ML Kit callback must never switch the user back to Local
            // after they explicitly chose Online.
            activateLocalAfterModelInstall = false
        } else if (localModelInstaller != null || !localBundleInstalled()) {
            startLocalModelDownload(
                kind = ModelInstallKind.FULL_BUNDLE,
                installer = localModelInstaller,
            )
            return
        }

        val controller = speechModeController ?: run {
            dispatch(
                VoiceBridgeAction.SpeechModeSelectionFailed(
                    "Recognition mode switching is unavailable in this build.",
                ),
            )
            return
        }

        controller.selectMode(mode).fold(
            onSuccess = {},
            onFailure = { throwable ->
                dispatch(
                    VoiceBridgeAction.SpeechModeSelectionFailed(
                        throwable.message ?: "unknown error",
                    ),
                )
            },
        )
    }

    fun onPushToTalkPressed() {
        val state = mutableUiState.value
        onPushToTalkPressed(
            SpeechDirection(
                sourceLanguage = state.configuredSourceLanguage,
                targetLanguage = state.configuredTargetLanguage,
            ),
        )
    }

    fun onPushToTalkPressed(direction: SpeechDirection) {
        if (recognitionIsActive() || mutableUiState.value.isSpeechPreparing) return

        textSpeaker?.stop()
        cancelTranslation()
        cancelCurrencyConversion()
        dispatch(VoiceBridgeAction.PressStarted(direction))
        speechEngine.startListening(direction.sourceLanguage.languageTag)
    }

    fun onPushToTalkReleased() {
        val activeDirection = mutableUiState.value.activeSpeechDirection ?: return
        onPushToTalkReleased(activeDirection)
    }

    fun onPushToTalkReleased(direction: SpeechDirection) {
        val state = mutableUiState.value
        if (!state.isListening || state.activeSpeechDirection != direction) return

        dispatch(VoiceBridgeAction.PressReleased(direction))
        speechEngine.stopListening()
    }

    fun onTranscriptChanged(text: String) {
        if (recognitionIsActive()) return
        dispatch(VoiceBridgeAction.TranscriptEdited(text))
        translateCurrentText(delayMillis = EDIT_TRANSLATION_DEBOUNCE_MS)
        refreshCurrencyConversion(delayMillis = EDIT_TRANSLATION_DEBOUNCE_MS)
    }

    fun onPermissionGranted() {
        dispatch(VoiceBridgeAction.PermissionGranted)
    }

    fun onPermissionDenied(permanently: Boolean) {
        dispatch(VoiceBridgeAction.PermissionDenied(permanently))
    }

    fun onHostStopped() {
        textSpeaker?.stop()
        if (mutableUiState.value.isSpeaking) {
            dispatch(VoiceBridgeAction.SpeakingFinished)
        }
        if (!recognitionIsActive()) return
        cancelTranslation()
        cancelCurrencyConversion()
        speechEngine.cancelListening()
        dispatch(VoiceBridgeAction.SpeechStopped)
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.ModeChanged -> dispatch(
                VoiceBridgeAction.SpeechModeSelected(
                    mode = event.mode,
                    backendName = event.backendName,
                    localModelInstalled = localBundleInstalled(),
                    localModeAvailable = localBundleInstalled() &&
                        speechModeController?.localModeAvailable == true,
                ),
            )

            is SpeechEvent.ModeChangeFailed -> dispatch(
                VoiceBridgeAction.SpeechModeFallback(
                    message = event.message,
                    mode = event.fallbackMode,
                    backendName = event.fallbackBackendName,
                    localModelInstalled = localBundleInstalled(),
                    localModeAvailable = localBundleInstalled() &&
                        speechModeController?.localModeAvailable == true,
                ),
            )

            SpeechEvent.Preparing -> dispatch(VoiceBridgeAction.SpeechEnginePreparing)
            SpeechEvent.Ready -> dispatch(VoiceBridgeAction.SpeechEngineReady)
            SpeechEvent.Listening -> {
                if (!mutableUiState.value.isProcessing) {
                    dispatch(VoiceBridgeAction.SpeechListening)
                }
            }
            SpeechEvent.Processing -> dispatch(VoiceBridgeAction.SpeechProcessing)
            is SpeechEvent.PartialResult -> {
                dispatch(VoiceBridgeAction.SpeechPartial(event.text))
                translateCurrentText(
                    delayMillis = PARTIAL_TRANSLATION_DEBOUNCE_MS,
                    includeBackTranslation = false,
                )
                refreshCurrencyConversion(delayMillis = 0)
            }

            is SpeechEvent.FinalResult -> {
                dispatch(VoiceBridgeAction.SpeechFinal(event.text))
                translateCurrentText(delayMillis = 0)
                refreshCurrencyConversion(delayMillis = 0)
            }

            SpeechEvent.Stopped -> dispatch(VoiceBridgeAction.SpeechStopped)
            is SpeechEvent.Error -> {
                cancelTranslation()
                cancelCurrencyConversion()
                dispatch(VoiceBridgeAction.SpeechFailed(event.message))
            }

            is SpeechEvent.Unresponsive -> {
                cancelTranslation()
                cancelCurrencyConversion()
                dispatch(VoiceBridgeAction.SpeechFailed(event.message))
            }
        }
    }

    private fun onModelInstallEvent(
        event: LocalModelInstallEvent,
        kind: ModelInstallKind,
    ) {
        when (kind) {
            ModelInstallKind.FULL_BUNDLE -> onFullBundleInstallEvent(event)
            ModelInstallKind.SCREEN_TRANSLATION -> onScreenTranslationInstallEvent(event)
        }
    }

    private fun onFullBundleInstallEvent(event: LocalModelInstallEvent) {
        when (event) {
            is LocalModelInstallEvent.BundleProgress -> dispatch(
                VoiceBridgeAction.ModelDownloadProgress(
                    progress = event.progress,
                    status = buildString {
                        append(event.componentIndex)
                        append('/')
                        append(event.componentCount)
                        append(" · ")
                        append(event.componentName)
                        append(": ")
                        append(event.detail)
                    },
                ),
            )

            is LocalModelInstallEvent.CheckingStorage -> dispatch(
                VoiceBridgeAction.ModelDownloadProgress(
                    progress = 0f,
                    status = "Checking free space…",
                ),
            )

            is LocalModelInstallEvent.Downloading -> {
                val progress = if (event.totalBytes > 0L) {
                    event.downloadedBytes.toFloat() / event.totalBytes.toFloat()
                } else {
                    0f
                }
                dispatch(
                    VoiceBridgeAction.ModelDownloadProgress(
                        progress = progress,
                        status = "Downloading offline models: ${(progress * 100).toInt()}%",
                    ),
                )
            }

            is LocalModelInstallEvent.Verifying -> dispatch(
                VoiceBridgeAction.ModelDownloadProgress(
                    progress = null,
                    status = "Verifying file ${event.fileIndex} of ${event.fileCount}…",
                ),
            )

            LocalModelInstallEvent.Activating -> dispatch(
                VoiceBridgeAction.ModelDownloadProgress(
                    progress = null,
                    status = "Installing an offline model…",
                ),
            )

            is LocalModelInstallEvent.Installed -> {
                dispatchModelDownloadCompleted()
                activateDownloadedLocalMode()
            }

            is LocalModelInstallEvent.Failed -> {
                activateLocalAfterModelInstall = false
                dispatch(
                    VoiceBridgeAction.ModelDownloadFailed(
                        message = event.message,
                        localModelInstalled = localBundleInstalled(),
                        localModeAvailable = localBundleAvailable(),
                    ),
                )
            }
        }
    }

    private fun onScreenTranslationInstallEvent(event: LocalModelInstallEvent) {
        when (event) {
            is LocalModelInstallEvent.BundleProgress -> dispatch(
                VoiceBridgeAction.ScreenModelDownloadProgress(
                    progress = event.progress,
                    status = buildString {
                        append(event.componentIndex)
                        append('/')
                        append(event.componentCount)
                        append(" · ")
                        append(event.componentName)
                        append(": ")
                        append(event.detail)
                    },
                ),
            )

            is LocalModelInstallEvent.CheckingStorage -> dispatch(
                VoiceBridgeAction.ScreenModelDownloadProgress(
                    progress = 0f,
                    status = "Checking translation models…",
                ),
            )

            is LocalModelInstallEvent.Downloading -> {
                val progress = if (event.totalBytes > 0L) {
                    event.downloadedBytes.toFloat() / event.totalBytes.toFloat()
                } else {
                    0f
                }
                dispatch(
                    VoiceBridgeAction.ScreenModelDownloadProgress(
                        progress = progress,
                        status = "Downloading translation models: ${(progress * 100).toInt()}%",
                    ),
                )
            }

            is LocalModelInstallEvent.Verifying -> dispatch(
                VoiceBridgeAction.ScreenModelDownloadProgress(
                    progress = null,
                    status = "Verifying translation model ${event.fileIndex} of ${event.fileCount}…",
                ),
            )

            LocalModelInstallEvent.Activating -> dispatch(
                VoiceBridgeAction.ScreenModelDownloadProgress(
                    progress = null,
                    status = "Installing an offline translation model…",
                ),
            )

            is LocalModelInstallEvent.Installed ->
                dispatch(VoiceBridgeAction.ScreenModelDownloadCompleted)

            is LocalModelInstallEvent.Failed -> dispatchModelDownloadFailure(
                kind = ModelInstallKind.SCREEN_TRANSLATION,
                message = event.message,
            )
        }
    }

    private fun dispatchModelDownloadUnavailable(kind: ModelInstallKind) {
        dispatchModelDownloadFailure(
            kind = kind,
            message = "Model download is unavailable in this build.",
        )
    }

    private fun dispatchModelDownloadFailure(
        kind: ModelInstallKind,
        message: String,
    ) {
        when (kind) {
            ModelInstallKind.FULL_BUNDLE -> {
                activateLocalAfterModelInstall = false
                dispatch(
                    VoiceBridgeAction.ModelDownloadFailed(
                        message = message,
                        localModelInstalled = localBundleInstalled(),
                        localModeAvailable = localBundleAvailable(),
                    ),
                )
            }

            ModelInstallKind.SCREEN_TRANSLATION -> dispatch(
                VoiceBridgeAction.ScreenModelDownloadFailed(message),
            )
        }
    }

    private fun dispatchModelDownloadCompleted() {
        dispatch(
            VoiceBridgeAction.ModelDownloadCompleted(
                localModeAvailable = localBundleAvailable(),
            ),
        )
    }

    private fun activateDownloadedLocalMode() {
        if (!activateLocalAfterModelInstall) return
        activateLocalAfterModelInstall = false

        val controller = speechModeController ?: run {
            dispatch(
                VoiceBridgeAction.SpeechModeSelectionFailed(
                    "Recognition mode switching is unavailable in this build.",
                ),
            )
            return
        }
        controller.selectMode(SpeechRecognitionMode.NVIDIA_LOCAL).onFailure { throwable ->
            dispatch(
                VoiceBridgeAction.SpeechModeSelectionFailed(
                    throwable.message ?: "unknown error",
                ),
            )
        }
    }

    private fun speakText(text: String, language: AppLanguage) {
        if (recognitionIsActive() || text.isBlank()) return
        val speaker = textSpeaker ?: run {
            dispatch(
                VoiceBridgeAction.SpeakingFailed(
                    "Text-to-speech is unavailable in this build.",
                ),
            )
            return
        }
        dispatch(VoiceBridgeAction.SpeakingStarted)
        speaker.speak(text, language)
    }

    private fun onTextSpeakerEvent(event: TextSpeakerEvent) {
        when (event) {
            is TextSpeakerEvent.Started -> dispatch(VoiceBridgeAction.SpeakingStarted)
            is TextSpeakerEvent.Done -> dispatch(VoiceBridgeAction.SpeakingFinished)
            is TextSpeakerEvent.Error -> dispatch(
                VoiceBridgeAction.SpeakingFailed(event.message),
            )
        }
    }

    private fun translateCurrentText(
        delayMillis: Long,
        includeBackTranslation: Boolean = true,
    ) {
        val state = mutableUiState.value
        val text = state.transcript.trim()
        cancelTranslation()

        if (text.isBlank()) return

        val revision = translationRevision
        translationJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            dispatch(VoiceBridgeAction.TranslationStarted)

            val result = try {
                translationEngine.translate(
                    TranslationRequest(
                        text = text,
                        sourceLanguage = state.sourceLanguage,
                        targetLanguage = state.targetLanguage,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }

            if (revision != translationRevision) return@launch

            val translatedText = result.getOrElse { throwable ->
                dispatch(
                    VoiceBridgeAction.TranslationFailed(
                        throwable.message ?: "unknown error",
                    ),
                )
                return@launch
            }.trim()

            if (translatedText.isBlank()) {
                dispatch(VoiceBridgeAction.TranslationUnavailable)
                return@launch
            }

            dispatch(
                VoiceBridgeAction.TranslationSucceeded(
                    sourceText = text,
                    text = translatedText,
                ),
            )

            val checker = backTranslationEngine
            if (
                !includeBackTranslation ||
                checker == null ||
                state.targetLanguage == AppLanguage.RUSSIAN
            ) {
                return@launch
            }

            dispatch(VoiceBridgeAction.BackTranslationStarted(translatedText))
            val backTranslationInput = translatedText.withTerminalPunctuationForTranslation()
            val backResult = try {
                checker.translate(
                    TranslationRequest(
                        text = backTranslationInput,
                        sourceLanguage = state.targetLanguage,
                        targetLanguage = AppLanguage.RUSSIAN,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }

            if (revision != translationRevision) return@launch

            var backTranslatedText = backResult.getOrNull()?.trim().orEmpty()
            if (backTranslatedText.requiresRussianRepair()) {
                val englishText = if (state.targetLanguage == AppLanguage.ENGLISH) {
                    translatedText
                } else {
                    val pivotResult = translateSafely(
                        engine = checker,
                        request = TranslationRequest(
                            text = backTranslationInput,
                            sourceLanguage = state.targetLanguage,
                            targetLanguage = AppLanguage.ENGLISH,
                        ),
                    )
                    if (revision != translationRevision) return@launch
                    pivotResult.getOrNull()?.trim().orEmpty()
                }

                backTranslatedText = if (englishText.isBlank()) {
                    ""
                } else {
                    val repairedResult = translateSafely(
                        engine = checker,
                        request = TranslationRequest(
                            text = englishText.withTerminalPunctuationForTranslation(),
                            sourceLanguage = AppLanguage.ENGLISH,
                            targetLanguage = AppLanguage.RUSSIAN,
                        ),
                    )
                    if (revision != translationRevision) return@launch
                    repairedResult.getOrNull()?.trim().orEmpty()
                }

                if (backTranslatedText.requiresRussianRepair()) {
                    backTranslatedText = ""
                }
            }

            dispatch(
                if (backTranslatedText.isBlank()) {
                    VoiceBridgeAction.BackTranslationUnavailable(translatedText)
                } else {
                    VoiceBridgeAction.BackTranslationSucceeded(
                        sourceText = translatedText,
                        text = backTranslatedText,
                    )
                },
            )
        }
    }

    private suspend fun translateSafely(
        engine: TranslationEngine,
        request: TranslationRequest,
    ): Result<String> = try {
        engine.translate(request)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private fun String.requiresRussianRepair(): Boolean {
        if (any { it in '\u0400'..'\u052F' }) return false
        return LATIN_WORD.findAll(this).take(2).count() == 2
    }

    private fun String.withTerminalPunctuationForTranslation(): String {
        val value = trim()
        if (value.none(Char::isLetter) || TERMINAL_PUNCTUATION.containsMatchIn(value)) {
            return value
        }
        return "$value."
    }

    private fun cancelTranslation(resetUiState: Boolean = true) {
        translationRevision += 1
        translationJob?.cancel()
        translationJob = null
        if (
            resetUiState &&
            mutableUiState.value.let { it.isTranslating || it.isBackTranslating }
        ) {
            dispatch(VoiceBridgeAction.TranslationCancelled)
        }
    }

    private fun refreshCurrencyConversion(delayMillis: Long) {
        val state = mutableUiState.value
        val text = state.transcript.trim()
        cancelCurrencyConversion()

        val provider = vndRubRateProvider ?: return
        if (text.isBlank()) return

        val amountsVnd = UniversalVndAmountParser.parseAllVnd(text)
        if (amountsVnd.isEmpty()) return
        val direction = SpeechDirection(state.sourceLanguage, state.targetLanguage)
        val revision = currencyConversionRevision
        val immediateRate = runCatching(provider::cachedRate).getOrNull()

        dispatch(
            VoiceBridgeAction.CurrencyConversionsUpdated(
                sourceText = text,
                direction = direction,
                conversions = immediateRate
                    ?.let { rate -> conversionsForRate(text, direction, amountsVnd, rate) }
                    .orEmpty()
                    .ifEmpty {
                        unresolvedConversions(
                            text = text,
                            direction = direction,
                            amountsVnd = amountsVnd,
                            state = CurrencyConversionState.LOADING,
                        )
                    },
            ),
        )

        currencyConversionJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)

            val result = try {
                provider.latestRate()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }

            if (revision != currencyConversionRevision) return@launch
            val current = mutableUiState.value
            if (
                current.transcript.trim() != text ||
                current.sourceLanguage != direction.sourceLanguage ||
                current.targetLanguage != direction.targetLanguage
            ) {
                return@launch
            }

            result.onSuccess { rate ->
                val conversions = conversionsForRate(text, direction, amountsVnd, rate)

                if (conversions.isNotEmpty()) {
                    dispatch(
                        VoiceBridgeAction.CurrencyConversionsUpdated(
                            sourceText = text,
                            direction = direction,
                            conversions = conversions,
                        ),
                    )
                }
            }.onFailure {
                if (immediateRate == null) {
                    dispatch(
                        VoiceBridgeAction.CurrencyConversionsUpdated(
                            sourceText = text,
                            direction = direction,
                            conversions = unresolvedConversions(
                                text = text,
                                direction = direction,
                                amountsVnd = amountsVnd,
                                state = CurrencyConversionState.UNAVAILABLE,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun conversionsForRate(
        text: String,
        direction: SpeechDirection,
        amountsVnd: List<Long>,
        rate: VndRubRate,
    ): List<CurrencyConversionUi> = amountsVnd.mapNotNull { amountVnd ->
        val estimate = runCatching {
            bufferedCurrencyEstimate(amountVnd, rate)
        }.getOrNull() ?: return@mapNotNull null

        CurrencyConversionUi(
            sourceText = text,
            direction = direction,
            amountVnd = amountVnd,
            estimatedRubles = estimate.estimatedRubles,
            estimatedUsdCents = estimate.estimatedUsdCents,
            estimatedEurCents = estimate.estimatedEurCents,
            estimatedUahKopecks = estimate.estimatedUahKopecks,
            exchangeBufferPercent = EXCHANGE_BUFFER_PERCENT,
            cbrEffectiveDate = rate.cbrEffectiveDate,
            isStale = rate.isStale,
            state = CurrencyConversionState.AVAILABLE,
        )
    }

    private fun bufferedCurrencyEstimate(
        amountVnd: Long,
        rate: VndRubRate,
    ): QuickCurrencyEstimateUi {
        val conversion = rate.convert(BigDecimal.valueOf(amountVnd))
        return QuickCurrencyEstimateUi(
            estimatedRubles = conversion
                .estimatedRublesWithBuffer(EXCHANGE_BUFFER_PERCENT)
                .longValueExact(),
            estimatedUsdCents = conversion
                .estimatedUsdWithBuffer(EXCHANGE_BUFFER_PERCENT)
                .movePointRight(2)
                .longValueExact(),
            estimatedEurCents = conversion
                .estimatedEurWithBuffer(EXCHANGE_BUFFER_PERCENT)
                .movePointRight(2)
                .longValueExact(),
            estimatedUahKopecks = conversion
                .estimatedUahWithBuffer(EXCHANGE_BUFFER_PERCENT)
                .movePointRight(2)
                .longValueExact(),
        )
    }

    private fun unresolvedConversions(
        text: String,
        direction: SpeechDirection,
        amountsVnd: List<Long>,
        state: CurrencyConversionState,
    ): List<CurrencyConversionUi> = amountsVnd.map { amountVnd ->
        CurrencyConversionUi(
            sourceText = text,
            direction = direction,
            amountVnd = amountVnd,
            exchangeBufferPercent = EXCHANGE_BUFFER_PERCENT,
            state = state,
        )
    }

    private fun cancelCurrencyConversion() {
        currencyConversionRevision += 1
        currencyConversionJob?.cancel()
        currencyConversionJob = null
    }

    private fun recognitionIsActive(): Boolean =
        mutableUiState.value.let { it.isListening || it.isProcessing }

    /**
     * With an installer this means the complete speech + translation bundle.
     * Test/minimal builds without one keep the speech controller's legacy signal.
     */
    private fun localBundleInstalled(): Boolean =
        runCatching { localModelInstaller?.isInstalled }
            .getOrNull()
            ?: (speechModeController?.localModelInstalled == true)

    private fun localBundleAvailable(): Boolean =
        localBundleInstalled() && speechModeController?.localModeAvailable == true

    private fun persistConfiguredLanguagePair() {
        val state = mutableUiState.value
        runCatching {
            languagePairStore?.save(
                LanguagePair(
                    sourceLanguage = state.configuredSourceLanguage,
                    targetLanguage = state.configuredTargetLanguage,
                ),
            )
        }
    }

    private fun dispatch(action: VoiceBridgeAction) {
        mutableUiState.update { state -> VoiceBridgeReducer.reduce(state, action) }
    }

    override fun onCleared() {
        cancelTranslation(resetUiState = false)
        cancelCurrencyConversion()
        modelInstallJob?.cancel()
        localModelInstaller?.close()
        if (screenTranslationModelInstaller !== localModelInstaller) {
            screenTranslationModelInstaller?.close()
        }
        textSpeaker?.close()
        speechEngine.destroy()
        if (backTranslationEngine !== translationEngine) {
            backTranslationEngine?.close()
        }
        translationEngine.close()
        super.onCleared()
    }

    class Factory(
        context: Context,
        private val translationEngine: TranslationEngine? = null,
    ) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VoiceBridgeViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }

            val mainTranslationEngine = translationEngine ?: GoogleMlKitTranslationEngine()
            return VoiceBridgeViewModel(
                speechEngine = SwitchableSpeechEngine(appContext),
                translationEngine = mainTranslationEngine,
                backTranslationEngine = mainTranslationEngine,
                localModelInstaller = CombinedLocalModelInstaller(appContext),
                screenTranslationModelInstaller = ScreenTranslationModelInstaller(appContext),
                textSpeaker = AndroidTextSpeaker(appContext),
                languagePairStore = SharedPreferencesLanguagePairStore(appContext),
                currencySelectionStore = SharedPreferencesCurrencySelectionStore(appContext),
                vndRubRateProvider = AndroidCbrVndRubRateProvider(appContext),
            ) as T
        }
    }

    private enum class ModelInstallKind {
        FULL_BUNDLE,
        SCREEN_TRANSLATION,
    }

    private companion object {
        val LATIN_WORD = Regex("[A-Za-z]+")
        val TERMINAL_PUNCTUATION = Regex("[.!?…][\\\"'»”’)}\\]]*$")
        const val PARTIAL_TRANSLATION_DEBOUNCE_MS = 450L
        const val EDIT_TRANSLATION_DEBOUNCE_MS = 350L
        const val EXCHANGE_BUFFER_PERCENT = 5
    }
}
