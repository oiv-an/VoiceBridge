package pro.ivol.voicebridge.ui

import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.ConversionCurrency
import pro.ivol.voicebridge.domain.CurrencySelection
import pro.ivol.voicebridge.domain.SpeechRecognitionMode

data class SpeechDirection(
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
) {
    init {
        require(sourceLanguage != targetLanguage) {
            "Speech source and target languages must be different."
        }
    }
}

enum class CurrencyConversionState {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

data class QuickCurrencyEstimateUi(
    val estimatedRubles: Long,
    val estimatedUsdCents: Long,
    val estimatedEurCents: Long,
    val estimatedUahKopecks: Long,
) {
    init {
        require(estimatedRubles >= 0L) { "RUB estimate cannot be negative." }
        require(estimatedUsdCents >= 0L) { "USD estimate cannot be negative." }
        require(estimatedEurCents >= 0L) { "EUR estimate cannot be negative." }
        require(estimatedUahKopecks >= 0L) { "UAH estimate cannot be negative." }
    }
}

data class CurrencyConversionUi(
    val sourceText: String,
    val direction: SpeechDirection,
    val amountVnd: Long,
    val estimatedRubles: Long? = null,
    val estimatedUsdCents: Long? = null,
    val estimatedEurCents: Long? = null,
    val estimatedUahKopecks: Long? = null,
    val exchangeBufferPercent: Int,
    val cbrEffectiveDate: String? = null,
    val isStale: Boolean = false,
    val state: CurrencyConversionState = CurrencyConversionState.AVAILABLE,
) {
    init {
        require(amountVnd >= 1_000L) { "Displayed VND amount must be at least 1,000." }
        require(exchangeBufferPercent in 0..100) { "Invalid exchange buffer." }
        when (state) {
            CurrencyConversionState.AVAILABLE -> {
                require(estimatedRubles != null && estimatedRubles >= 0L) {
                    "Available conversion needs a RUB amount."
                }
                require(estimatedUsdCents != null && estimatedUsdCents >= 0L) {
                    "Available conversion needs a USD amount."
                }
                require(estimatedEurCents != null && estimatedEurCents >= 0L) {
                    "Available conversion needs a EUR amount."
                }
                require(estimatedUahKopecks != null && estimatedUahKopecks >= 0L) {
                    "Available conversion needs a UAH amount."
                }
                require(!cbrEffectiveDate.isNullOrBlank()) {
                    "Available conversion needs a CBR date."
                }
            }
            CurrencyConversionState.LOADING,
            CurrencyConversionState.UNAVAILABLE,
            -> {
                require(
                    estimatedRubles == null &&
                        estimatedUsdCents == null &&
                        estimatedEurCents == null &&
                        estimatedUahKopecks == null &&
                        cbrEffectiveDate == null,
                ) {
                    "Unresolved conversion cannot contain a quote."
                }
            }
        }
    }
}

data class VoiceBridgeUiState(
    val speechBackend: String = "",
    val speechRecognitionMode: SpeechRecognitionMode = SpeechRecognitionMode.ONLINE,
    val localModelInstalled: Boolean = false,
    val localSpeechAvailable: Boolean = false,
    val isModelDownloading: Boolean = false,
    val modelDownloadProgress: Float? = null,
    val modelDownloadStatus: String? = null,
    val isScreenModelDownloading: Boolean = false,
    val screenModelDownloadProgress: Float? = null,
    val screenModelDownloadStatus: String? = null,
    val translationBackend: String = "",
    val sourceLanguage: AppLanguage = AppLanguage.RUSSIAN,
    val targetLanguage: AppLanguage = AppLanguage.VIETNAMESE,
    val configuredSourceLanguage: AppLanguage = sourceLanguage,
    val configuredTargetLanguage: AppLanguage = targetLanguage,
    val selectedCurrencies: Set<ConversionCurrency> = CurrencySelection.DEFAULT_CURRENCIES,
    val transcript: String = "",
    val translation: String = "",
    val translatedSourceText: String? = null,
    val backTranslation: String = "",
    val backTranslatedSourceText: String? = null,
    val currencyConversions: List<CurrencyConversionUi> = emptyList(),
    val isSpeechPreparing: Boolean = false,
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val activeSpeechDirection: SpeechDirection? = null,
    val isTranslating: Boolean = false,
    val isBackTranslating: Boolean = false,
    val isSpeaking: Boolean = false,
    val microphonePermissionPermanentlyDenied: Boolean = false,
    val status: String = "Hold a button and speak.",
    val error: String? = null,
)

internal fun VoiceBridgeUiState.vietnameseTextForPronunciation(): String? = when {
    sourceLanguage == AppLanguage.VIETNAMESE ->
        transcript.trim().takeIf(String::isNotEmpty)

    targetLanguage == AppLanguage.VIETNAMESE &&
        translatedSourceText == transcript.trim() ->
        translation.trim().takeIf(String::isNotEmpty)

    else -> null
}

sealed interface VoiceBridgeAction {
    data class SourceLanguageSelected(val language: AppLanguage) : VoiceBridgeAction

    data class TargetLanguageSelected(val language: AppLanguage) : VoiceBridgeAction

    data object LanguagesSwapped : VoiceBridgeAction

    data class CurrencySelectionChanged(
        val currencies: Set<ConversionCurrency>,
    ) : VoiceBridgeAction

    data class SpeechModeSelected(
        val mode: SpeechRecognitionMode,
        val backendName: String,
        val localModelInstalled: Boolean,
        val localModeAvailable: Boolean,
    ) : VoiceBridgeAction

    data class SpeechModeSelectionFailed(val message: String) : VoiceBridgeAction

    data class SpeechModeFallback(
        val message: String,
        val mode: SpeechRecognitionMode,
        val backendName: String,
        val localModelInstalled: Boolean,
        val localModeAvailable: Boolean,
    ) : VoiceBridgeAction

    data object ModelDownloadStarted : VoiceBridgeAction

    data class ModelDownloadProgress(
        val progress: Float?,
        val status: String,
    ) : VoiceBridgeAction

    data class ModelDownloadCompleted(
        val localModeAvailable: Boolean,
    ) : VoiceBridgeAction

    data class ModelDownloadCancelled(
        val localModelInstalled: Boolean,
        val localModeAvailable: Boolean,
    ) : VoiceBridgeAction

    data class ModelDownloadFailed(
        val message: String,
        val localModelInstalled: Boolean,
        val localModeAvailable: Boolean,
    ) : VoiceBridgeAction

    data object ScreenModelDownloadStarted : VoiceBridgeAction

    data class ScreenModelDownloadProgress(
        val progress: Float?,
        val status: String,
    ) : VoiceBridgeAction

    data object ScreenModelDownloadCompleted : VoiceBridgeAction

    data object ScreenModelDownloadCancelled : VoiceBridgeAction

    data class ScreenModelDownloadFailed(val message: String) : VoiceBridgeAction

    data object SpeakingStarted : VoiceBridgeAction

    data object SpeakingFinished : VoiceBridgeAction

    data class SpeakingFailed(val message: String) : VoiceBridgeAction

    data object SpeechEnginePreparing : VoiceBridgeAction

    data object SpeechEngineReady : VoiceBridgeAction

    data class PressStarted(val direction: SpeechDirection) : VoiceBridgeAction

    data class PressReleased(val direction: SpeechDirection) : VoiceBridgeAction

    data object SpeechListening : VoiceBridgeAction

    data object SpeechProcessing : VoiceBridgeAction

    data class SpeechPartial(val text: String) : VoiceBridgeAction

    data class SpeechFinal(val text: String) : VoiceBridgeAction

    data object SpeechStopped : VoiceBridgeAction

    data class SpeechFailed(val message: String) : VoiceBridgeAction

    data class TranscriptEdited(val text: String) : VoiceBridgeAction

    data object TranslationStarted : VoiceBridgeAction

    data class TranslationSucceeded(
        val sourceText: String,
        val text: String,
    ) : VoiceBridgeAction

    data class BackTranslationStarted(val sourceText: String) : VoiceBridgeAction

    data class BackTranslationSucceeded(
        val sourceText: String,
        val text: String,
    ) : VoiceBridgeAction

    data class BackTranslationUnavailable(val sourceText: String) : VoiceBridgeAction

    data object TranslationCancelled : VoiceBridgeAction

    data object TranslationUnavailable : VoiceBridgeAction

    data class TranslationFailed(val message: String) : VoiceBridgeAction

    data class CurrencyConversionsUpdated(
        val sourceText: String,
        val direction: SpeechDirection,
        val conversions: List<CurrencyConversionUi>,
    ) : VoiceBridgeAction

    data object PermissionGranted : VoiceBridgeAction

    data class PermissionDenied(val permanently: Boolean) : VoiceBridgeAction
}

object VoiceBridgeReducer {
    fun reduce(
        state: VoiceBridgeUiState,
        action: VoiceBridgeAction,
    ): VoiceBridgeUiState = when (action) {
        is VoiceBridgeAction.SourceLanguageSelected -> selectSource(state, action.language)
        is VoiceBridgeAction.TargetLanguageSelected -> selectTarget(state, action.language)
        VoiceBridgeAction.LanguagesSwapped -> swapLanguages(state)
        is VoiceBridgeAction.CurrencySelectionChanged -> if (action.currencies.isEmpty()) {
            state
        } else {
            state.copy(selectedCurrencies = action.currencies)
        }

        is VoiceBridgeAction.SpeechModeSelected -> state.copy(
            speechRecognitionMode = action.mode,
            speechBackend = action.backendName,
            localModelInstalled = action.localModelInstalled,
            localSpeechAvailable = action.localModeAvailable,
            isSpeechPreparing = if (action.mode == SpeechRecognitionMode.ONLINE) {
                false
            } else {
                state.isSpeechPreparing
            },
            status = when (action.mode) {
                SpeechRecognitionMode.ONLINE -> "Online recognition is enabled."
                SpeechRecognitionMode.NVIDIA_LOCAL ->
                    "Local recognition is enabled. The first start may take a few seconds."
            },
            error = null,
        )

        is VoiceBridgeAction.SpeechModeSelectionFailed -> state.copy(
            status = "Could not switch speech recognition.",
            error = action.message,
        )

        is VoiceBridgeAction.SpeechModeFallback -> state.copy(
            speechRecognitionMode = action.mode,
            speechBackend = action.backendName,
            localModelInstalled = action.localModelInstalled,
            localSpeechAvailable = action.localModeAvailable,
            isSpeechPreparing = false,
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            status = "Local recognition could not load. Google is still available.",
            error = action.message,
        )

        VoiceBridgeAction.ModelDownloadStarted -> state.copy(
            isModelDownloading = true,
            modelDownloadProgress = 0f,
            modelDownloadStatus = "Preparing the local model download…",
            error = null,
        )

        is VoiceBridgeAction.ModelDownloadProgress -> state.copy(
            isModelDownloading = true,
            modelDownloadProgress = action.progress,
            modelDownloadStatus = action.status,
        )

        is VoiceBridgeAction.ModelDownloadCompleted -> state.copy(
            localModelInstalled = true,
            localSpeechAvailable = action.localModeAvailable,
            isModelDownloading = false,
            modelDownloadProgress = 1f,
            modelDownloadStatus = "All offline models are installed.",
            status = "The offline bundle is ready.",
            error = null,
        )

        is VoiceBridgeAction.ModelDownloadCancelled -> state.copy(
            localModelInstalled = action.localModelInstalled,
            localSpeechAvailable = action.localModeAvailable,
            isModelDownloading = false,
            modelDownloadProgress = null,
            modelDownloadStatus = "Download paused. You can resume it later.",
        )

        is VoiceBridgeAction.ModelDownloadFailed -> state.copy(
            localModelInstalled = action.localModelInstalled,
            localSpeechAvailable = action.localModeAvailable,
            isModelDownloading = false,
            modelDownloadProgress = null,
            modelDownloadStatus = "Could not download all offline models.",
            error = action.message,
        )

        VoiceBridgeAction.ScreenModelDownloadStarted -> state.copy(
            isScreenModelDownloading = true,
            screenModelDownloadProgress = 0f,
            screenModelDownloadStatus = "Preparing offline screen translation…",
        )

        is VoiceBridgeAction.ScreenModelDownloadProgress -> state.copy(
            isScreenModelDownloading = true,
            screenModelDownloadProgress = action.progress,
            screenModelDownloadStatus = action.status,
        )

        VoiceBridgeAction.ScreenModelDownloadCompleted -> state.copy(
            isScreenModelDownloading = false,
            screenModelDownloadProgress = 1f,
            screenModelDownloadStatus = "Offline screen translation is ready.",
        )

        VoiceBridgeAction.ScreenModelDownloadCancelled -> state.copy(
            isScreenModelDownloading = false,
            screenModelDownloadProgress = null,
            screenModelDownloadStatus = "Download paused. You can resume it later.",
        )

        is VoiceBridgeAction.ScreenModelDownloadFailed -> state.copy(
            isScreenModelDownloading = false,
            screenModelDownloadProgress = null,
            screenModelDownloadStatus = action.message,
        )

        VoiceBridgeAction.SpeakingStarted -> state.copy(
            isSpeaking = true,
            status = "Speaking…",
            error = null,
        )

        VoiceBridgeAction.SpeakingFinished -> state.copy(
            isSpeaking = false,
            status = activeStatus(state, "Ready."),
        )

        is VoiceBridgeAction.SpeakingFailed -> state.copy(
            isSpeaking = false,
            status = "Could not speak the text.",
            error = action.message,
        )

        VoiceBridgeAction.SpeechEnginePreparing -> state.copy(
            isSpeechPreparing = true,
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            status = "Loading local recognition into memory…",
            error = null,
        )

        VoiceBridgeAction.SpeechEngineReady -> state.copy(
            isSpeechPreparing = false,
            status = "Local recognition is ready. Hold a button and speak.",
            error = null,
        )

        is VoiceBridgeAction.PressStarted -> state.copy(
            sourceLanguage = action.direction.sourceLanguage,
            targetLanguage = action.direction.targetLanguage,
            transcript = "",
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            currencyConversions = emptyList(),
            isSpeechPreparing = false,
            isListening = true,
            isProcessing = false,
            activeSpeechDirection = action.direction,
            isTranslating = false,
            isBackTranslating = false,
            isSpeaking = false,
            status = "Starting the microphone…",
            error = null,
        )

        is VoiceBridgeAction.PressReleased -> {
            if (!state.isListening || state.activeSpeechDirection != action.direction) {
                state
            } else {
                state.copy(
                    isListening = false,
                    isProcessing = true,
                    status = "Recognizing…",
                )
            }
        }

        VoiceBridgeAction.SpeechListening -> state.copy(
            isSpeechPreparing = false,
            isListening = true,
            isProcessing = false,
            status = "Listening…",
            error = null,
        )

        VoiceBridgeAction.SpeechProcessing -> state.copy(
            isListening = false,
            isProcessing = true,
            status = "Recognizing…",
        )

        is VoiceBridgeAction.SpeechPartial -> state.copy(
            transcript = action.text,
            translation = state.translation.takeIf {
                state.translatedSourceText == action.text
            }.orEmpty(),
            translatedSourceText = state.translatedSourceText.takeIf { it == action.text },
            backTranslation = "",
            backTranslatedSourceText = null,
            isBackTranslating = false,
            currencyConversions = state.currencyConversions.takeIf { conversions ->
                conversions.all {
                    it.sourceText == action.text.trim() &&
                        it.direction.sourceLanguage == state.sourceLanguage &&
                        it.direction.targetLanguage == state.targetLanguage
                }
            }.orEmpty(),
            status = if (state.isListening) "Listening…" else state.status,
            error = null,
        )

        is VoiceBridgeAction.SpeechFinal -> state.copy(
            transcript = action.text,
            translation = state.translation.takeIf {
                state.translatedSourceText == action.text
            }.orEmpty(),
            translatedSourceText = state.translatedSourceText.takeIf { it == action.text },
            backTranslation = "",
            backTranslatedSourceText = null,
            isBackTranslating = false,
            currencyConversions = state.currencyConversions.takeIf { conversions ->
                conversions.all {
                    it.sourceText == action.text.trim() &&
                        it.direction.sourceLanguage == state.sourceLanguage &&
                        it.direction.targetLanguage == state.targetLanguage
                }
            }.orEmpty(),
            isSpeechPreparing = false,
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            status = "Speech recognized.",
            error = null,
        )

        VoiceBridgeAction.SpeechStopped -> state.copy(
            isSpeechPreparing = false,
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            status = if (state.isTranslating) "Translating…" else "Ready.",
        )

        is VoiceBridgeAction.SpeechFailed -> state.copy(
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            currencyConversions = emptyList(),
            isSpeechPreparing = false,
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            isTranslating = false,
            isBackTranslating = false,
            status = "Could not recognize speech.",
            error = action.message,
        )

        is VoiceBridgeAction.TranscriptEdited -> state.copy(
            transcript = action.text,
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            isBackTranslating = false,
            currencyConversions = emptyList(),
            status = if (action.text.isBlank()) "Enter text or say a phrase." else "Text updated.",
            error = null,
        )

        VoiceBridgeAction.TranslationStarted -> state.copy(
            isTranslating = true,
            isBackTranslating = false,
            backTranslation = "",
            backTranslatedSourceText = null,
            status = if (state.isListening) "Listening and translating…" else "Translating…",
            error = null,
        )

        is VoiceBridgeAction.TranslationSucceeded -> state.copy(
            translation = action.text,
            translatedSourceText = action.sourceText,
            isTranslating = false,
            isBackTranslating = false,
            backTranslation = "",
            backTranslatedSourceText = null,
            status = activeStatus(state, "Translation ready."),
            error = null,
        )

        is VoiceBridgeAction.BackTranslationStarted -> if (
            state.translation != action.sourceText
        ) {
            state
        } else {
            state.copy(
                backTranslation = "",
                backTranslatedSourceText = action.sourceText,
                isBackTranslating = true,
            )
        }

        is VoiceBridgeAction.BackTranslationSucceeded -> if (
            state.translation != action.sourceText
        ) {
            state
        } else {
            state.copy(
                backTranslation = action.text,
                backTranslatedSourceText = action.sourceText,
                isBackTranslating = false,
            )
        }

        is VoiceBridgeAction.BackTranslationUnavailable -> if (
            state.translation != action.sourceText
        ) {
            state
        } else {
            state.copy(
                backTranslation = "",
                backTranslatedSourceText = null,
                isBackTranslating = false,
            )
        }

        VoiceBridgeAction.TranslationCancelled -> state.copy(
            isTranslating = false,
            isBackTranslating = false,
            status = activeStatus(state, state.status),
        )

        VoiceBridgeAction.TranslationUnavailable -> state.copy(
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            isTranslating = false,
            isBackTranslating = false,
            status = activeStatus(state, "Translation is not available yet."),
            error = null,
        )

        is VoiceBridgeAction.TranslationFailed -> state.copy(
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            isTranslating = false,
            isBackTranslating = false,
            status = activeStatus(state, "Could not translate the text."),
            error = "Translation error: ${action.message}",
        )

        is VoiceBridgeAction.CurrencyConversionsUpdated -> {
            if (
                action.conversions.isEmpty() ||
                state.transcript.trim() != action.sourceText ||
                action.direction.sourceLanguage != state.sourceLanguage ||
                action.direction.targetLanguage != state.targetLanguage ||
                action.conversions.any {
                    it.sourceText != action.sourceText || it.direction != action.direction
                }
            ) {
                state
            } else {
                state.copy(currencyConversions = action.conversions)
            }
        }

        VoiceBridgeAction.PermissionGranted -> state.copy(
            microphonePermissionPermanentlyDenied = false,
            status = "Hold a button and speak.",
            error = null,
        )

        is VoiceBridgeAction.PermissionDenied -> state.copy(
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            microphonePermissionPermanentlyDenied = action.permanently,
            status = "Microphone access is unavailable.",
            error = if (action.permanently) {
                "Allow microphone access for VoiceBridge in Android settings."
            } else {
                "Speech recognition needs microphone access."
            },
        )
    }

    private fun selectSource(
        state: VoiceBridgeUiState,
        language: AppLanguage,
    ): VoiceBridgeUiState {
        if (language == state.configuredSourceLanguage) {
            return alignCurrentDirectionWithConfiguredPair(state)
        }
        if (language == state.configuredTargetLanguage) return swapLanguages(state)

        return state.copy(
            sourceLanguage = language,
            targetLanguage = state.configuredTargetLanguage,
            configuredSourceLanguage = language,
            transcript = "",
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            currencyConversions = emptyList(),
            status = "Spoken language updated.",
            error = null,
        )
    }

    private fun selectTarget(
        state: VoiceBridgeUiState,
        language: AppLanguage,
    ): VoiceBridgeUiState {
        if (language == state.configuredTargetLanguage) {
            return alignCurrentDirectionWithConfiguredPair(state)
        }
        if (language == state.configuredSourceLanguage) return swapLanguages(state)
        val transcriptMatchesConfiguredSource =
            state.sourceLanguage == state.configuredSourceLanguage

        return state.copy(
            sourceLanguage = state.configuredSourceLanguage,
            targetLanguage = language,
            configuredTargetLanguage = language,
            transcript = state.transcript.takeIf { transcriptMatchesConfiguredSource }.orEmpty(),
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            currencyConversions = emptyList(),
            status = "Translation language updated.",
            error = null,
        )
    }

    private fun swapLanguages(state: VoiceBridgeUiState): VoiceBridgeUiState {
        val newSourceLanguage = state.configuredTargetLanguage
        val newTargetLanguage = state.configuredSourceLanguage
        val hasTranslation =
            state.translation.isNotBlank() && state.translatedSourceText == state.transcript
        val currentDirectionAlreadyMatches =
            state.sourceLanguage == newSourceLanguage && state.targetLanguage == newTargetLanguage
        val shouldSwapText = hasTranslation && !currentDirectionAlreadyMatches

        return state.copy(
            sourceLanguage = newSourceLanguage,
            targetLanguage = newTargetLanguage,
            configuredSourceLanguage = newSourceLanguage,
            configuredTargetLanguage = newTargetLanguage,
            transcript = when {
                currentDirectionAlreadyMatches -> state.transcript
                shouldSwapText -> state.translation
                else -> ""
            },
            translation = when {
                currentDirectionAlreadyMatches -> state.translation
                shouldSwapText -> state.transcript
                else -> ""
            },
            translatedSourceText = when {
                currentDirectionAlreadyMatches -> state.translatedSourceText
                shouldSwapText -> state.translation
                else -> null
            },
            backTranslation = "",
            backTranslatedSourceText = null,
            currencyConversions = emptyList(),
            isListening = false,
            isProcessing = false,
            activeSpeechDirection = null,
            isTranslating = false,
            isBackTranslating = false,
            status = "Languages swapped.",
            error = null,
        )
    }

    private fun alignCurrentDirectionWithConfiguredPair(
        state: VoiceBridgeUiState,
    ): VoiceBridgeUiState {
        if (
            state.sourceLanguage == state.configuredSourceLanguage &&
            state.targetLanguage == state.configuredTargetLanguage
        ) {
            return state
        }

        return state.copy(
            sourceLanguage = state.configuredSourceLanguage,
            targetLanguage = state.configuredTargetLanguage,
            transcript = "",
            translation = "",
            translatedSourceText = null,
            backTranslation = "",
            backTranslatedSourceText = null,
            isBackTranslating = false,
            currencyConversions = emptyList(),
            status = "Language direction updated.",
            error = null,
        )
    }

    private fun activeStatus(state: VoiceBridgeUiState, idleStatus: String): String = when {
        state.isListening -> "Listening…"
        state.isProcessing -> "Recognizing…"
        else -> idleStatus
    }
}
