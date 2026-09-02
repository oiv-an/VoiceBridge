package pro.ivol.voicebridge.ui

import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.ivol.voicebridge.R
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.ConversionCurrency
import pro.ivol.voicebridge.domain.SpeechRecognitionMode
import pro.ivol.voicebridge.screen.ScreenTranslationServiceState
import pro.ivol.voicebridge.translation.VietnamesePronunciation

@Composable
fun VoiceBridgeScreen(
    state: VoiceBridgeUiState,
    hasMicrophonePermission: Boolean,
    onSourceLanguageSelected: (AppLanguage) -> Unit,
    onTargetLanguageSelected: (AppLanguage) -> Unit,
    onSwapLanguages: () -> Unit,
    onCurrencyToggled: (ConversionCurrency) -> Unit,
    onSpeechModeSelected: (SpeechRecognitionMode) -> Unit,
    onDownloadLocalModel: () -> Unit,
    onDownloadScreenTranslationModels: () -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    onSpeakTranscript: () -> Unit,
    onSpeakTranslation: () -> Unit,
    onTranscriptChanged: (String) -> Unit,
    onEstimateCurrencies: (Long) -> QuickCurrencyEstimateUi?,
    onPushToTalkPressed: (SpeechDirection) -> Unit,
    onPushToTalkReleased: (SpeechDirection) -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    openSettingsRequest: Int,
    returnToMainRequest: Int,
    hasScreenOverlayPermission: Boolean,
    screenAccessibilityConfigured: Boolean,
    screenAccessibilityConnected: Boolean,
    screenTranslationModelsReady: Boolean?,
    screenTranslationModelsError: String?,
    screenTranslationState: ScreenTranslationServiceState,
    screenTranslationError: String?,
    onRequestScreenOverlayPermission: () -> Unit,
    onRequestScreenAccessibility: () -> Unit,
    showScreenRestrictedSettingsHint: Boolean,
    onOpenVoiceBridgeAppInfo: () -> Unit,
    onRetryScreenTranslationModelCheck: () -> Unit,
    onStartScreenTranslation: () -> Unit,
    onStopScreenTranslation: () -> Unit,
) {
    val controlsEnabled =
        !state.isSpeechPreparing && !state.isListening && !state.isProcessing
    val screenTranslationActive =
        screenTranslationState == ScreenTranslationServiceState.STOPPING ||
            screenTranslationState == ScreenTranslationServiceState.STARTING ||
            screenTranslationState == ScreenTranslationServiceState.READY ||
            screenTranslationState == ScreenTranslationServiceState.TRANSLATING ||
            screenTranslationState == ScreenTranslationServiceState.SHOWING
    val screenTranslationReadyToStart =
        hasScreenOverlayPermission &&
            screenAccessibilityConfigured &&
            screenAccessibilityConnected &&
            screenTranslationModelsReady == true &&
            !state.isScreenModelDownloading &&
            !state.isModelDownloading
    val forwardDirection = SpeechDirection(
        sourceLanguage = state.configuredSourceLanguage,
        targetLanguage = state.configuredTargetLanguage,
    )
    val reverseDirection = SpeechDirection(
        sourceLanguage = state.configuredTargetLanguage,
        targetLanguage = state.configuredSourceLanguage,
    )
    var showNumericInput by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val transcriptIsNumericAmount = state.transcript.isNumericAmount()
    val translationFontSize = translationFontSizeSp(state.translation)
    val vietnameseText = state.vietnameseTextForPronunciation()
    val vietnamesePronunciation = remember(
        vietnameseText,
        state.isListening,
        state.isProcessing,
    ) {
        vietnameseText
            ?.takeUnless { state.isListening || state.isProcessing }
            ?.let(VietnamesePronunciation::shortRussianHint)
    }

    LaunchedEffect(openSettingsRequest) {
        if (openSettingsRequest > 0) showSettings = true
    }

    LaunchedEffect(returnToMainRequest) {
        if (returnToMainRequest > 0) {
            showSettings = false
            showNumericInput = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextFieldActionHeader(
                title = stringResource(R.string.translation_label),
                onSpeak = onSpeakTranslation,
                speakEnabled = state.translation.isNotBlank() && controlsEnabled,
                speakDescription = stringResource(R.string.speak_translation),
                onSettings = { showSettings = true },
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = state.translation,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.translation_hint),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = translationFontSize.sp,
                            lineHeight = (translationFontSize + 8).sp,
                        ),
                    )
                },
                minLines = 3,
                maxLines = 6,
                readOnly = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.displaySmall.copy(
                    fontSize = translationFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (translationFontSize + 8).sp,
                ),
            )

            if (state.translation.isNotBlank()) {
                Text(
                    text = stringResource(R.string.google_translate_attribution),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, end = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                    ),
                    textAlign = TextAlign.End,
                )
            }

            if (
                state.targetLanguage != AppLanguage.RUSSIAN &&
                state.translation.isNotBlank() &&
                (
                    state.isBackTranslating ||
                        (
                            state.backTranslatedSourceText == state.translation &&
                                state.backTranslation.isNotBlank()
                            )
                    )
            ) {
                BackTranslationLine(
                    text = state.backTranslation,
                    isLoading = state.isBackTranslating,
                )
            }

            if (state.targetLanguage == AppLanguage.VIETNAMESE) {
                vietnamesePronunciation?.let { pronunciation ->
                    VietnamesePronunciationLine(pronunciation)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PushToTalkButton(
                    direction = forwardDirection,
                    directionLabel = directionLabel(forwardDirection),
                    idleLabel = stringResource(R.string.hold_to_talk),
                    listeningLabel = stringResource(R.string.listening),
                    processingLabel = stringResource(R.string.processing),
                    primaryStyle = true,
                    state = state,
                    hasMicrophonePermission = hasMicrophonePermission,
                    onPressStarted = onPushToTalkPressed,
                    onPressReleased = onPushToTalkReleased,
                    onPermissionRequired = onRequestMicrophonePermission,
                    modifier = Modifier.weight(1f),
                )

                PushToTalkButton(
                    direction = reverseDirection,
                    directionLabel = directionLabel(reverseDirection),
                    idleLabel = stringResource(R.string.hold_to_talk),
                    listeningLabel = stringResource(R.string.listening),
                    processingLabel = stringResource(R.string.processing),
                    primaryStyle = false,
                    state = state,
                    hasMicrophonePermission = hasMicrophonePermission,
                    onPressStarted = onPushToTalkPressed,
                    onPressReleased = onPushToTalkReleased,
                    onPermissionRequired = onRequestMicrophonePermission,
                    modifier = Modifier.weight(1f),
                )
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = error,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (!hasMicrophonePermission) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.microphone_permission_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRequestMicrophonePermission) {
                    Text(
                        stringResource(
                            if (state.microphonePermissionPermanentlyDenied) {
                                R.string.open_app_settings
                            } else {
                                R.string.grant_microphone_permission
                            },
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            TextFieldActionHeader(
                title = stringResource(R.string.transcript_label),
                onSpeak = onSpeakTranscript,
                speakEnabled = state.transcript.isNotBlank() && controlsEnabled,
                speakDescription = stringResource(R.string.speak_transcript),
                onNumericInput = { showNumericInput = true },
                numericInputEnabled = controlsEnabled,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.transcript,
                    onValueChange = onTranscriptChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.transcript_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = controlsEnabled,
                    readOnly = transcriptIsNumericAmount,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = GroupedLargeNumberVisualTransformation,
                    textStyle = if (transcriptIsNumericAmount) {
                        MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )

                if (transcriptIsNumericAmount && controlsEnabled) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                role = Role.Button,
                                onClick = { showNumericInput = true },
                            )
                            .semantics {
                                contentDescription = "Edit amount in the large calculator"
                            },
                    )
                }
            }

            if (state.sourceLanguage == AppLanguage.VIETNAMESE) {
                vietnamesePronunciation?.let { pronunciation ->
                    VietnamesePronunciationLine(pronunciation)
                }
            }

            state.currencyConversions.forEach { conversion ->
                Spacer(modifier = Modifier.height(10.dp))
                CurrencyConversionCard(
                    conversion = conversion,
                    selectedCurrencies = state.selectedCurrencies,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            if (screenTranslationActive) {
                OutlinedButton(
                    onClick = onStopScreenTranslation,
                    enabled = screenTranslationState != ScreenTranslationServiceState.STOPPING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (screenTranslationState == ScreenTranslationServiceState.STOPPING) {
                                R.string.screen_translation_stopping
                            } else {
                                R.string.stop_screen_translation
                            },
                        ),
                    )
                }
            } else {
                Button(
                    onClick = {
                        if (screenTranslationReadyToStart) {
                            onStartScreenTranslation()
                        } else {
                            showSettings = true
                        }
                    },
                    enabled = !state.isScreenModelDownloading && !state.isModelDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.start_screen_translation))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showSettings) {
        VoiceBridgeSettingsDialog(
            sourceLanguage = state.configuredSourceLanguage,
            targetLanguage = state.configuredTargetLanguage,
            selectedCurrencies = state.selectedCurrencies,
            selectedMode = state.speechRecognitionMode,
            localModelInstalled = state.localModelInstalled,
            localModeAvailable = state.localSpeechAvailable,
            isModelDownloading = state.isModelDownloading,
            modelDownloadProgress = state.modelDownloadProgress,
            modelDownloadStatus = state.modelDownloadStatus,
            isScreenModelDownloading =
                state.isScreenModelDownloading || state.isModelDownloading,
            screenModelDownloadProgress = if (state.isScreenModelDownloading) {
                state.screenModelDownloadProgress
            } else {
                state.modelDownloadProgress
            },
            screenModelDownloadStatus = if (state.isScreenModelDownloading) {
                state.screenModelDownloadStatus
            } else {
                state.modelDownloadStatus
            },
            enabled = controlsEnabled && !state.isScreenModelDownloading,
            onSourceLanguageSelected = onSourceLanguageSelected,
            onTargetLanguageSelected = onTargetLanguageSelected,
            onSwapLanguages = onSwapLanguages,
            onCurrencyToggled = onCurrencyToggled,
            onModeSelected = onSpeechModeSelected,
            onDownloadModel = onDownloadLocalModel,
            onDownloadScreenModels = onDownloadScreenTranslationModels,
            onCancelDownload = onCancelLocalModelDownload,
            hasScreenOverlayPermission = hasScreenOverlayPermission,
            screenAccessibilityConfigured = screenAccessibilityConfigured,
            screenAccessibilityConnected = screenAccessibilityConnected,
            screenTranslationModelsReady = screenTranslationModelsReady,
            screenTranslationModelsError = screenTranslationModelsError,
            screenTranslationState = screenTranslationState,
            screenTranslationError = screenTranslationError,
            onRequestScreenOverlayPermission = onRequestScreenOverlayPermission,
            onRequestScreenAccessibility = onRequestScreenAccessibility,
            showRestrictedSettingsHint = showScreenRestrictedSettingsHint,
            onOpenAppInfo = onOpenVoiceBridgeAppInfo,
            onRetryScreenModelCheck = onRetryScreenTranslationModelCheck,
            onStartScreenTranslation = onStartScreenTranslation,
            onStopScreenTranslation = onStopScreenTranslation,
            onDismiss = { showSettings = false },
        )
    }

    if (showNumericInput) {
        NumericInputDialog(
            initialValue = state.transcript,
            selectedCurrencies = state.selectedCurrencies,
            estimateCurrencies = onEstimateCurrencies,
            onDismiss = { showNumericInput = false },
            onConfirm = { number ->
                showNumericInput = false
                onTranscriptChanged(number)
            },
        )
    }
}

@Composable
private fun BackTranslationLine(
    text: String,
    isLoading: Boolean,
) {
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = stringResource(R.string.back_translation_label),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                ),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(1.dp))
            SelectionContainer {
                Text(
                    text = if (isLoading) {
                        stringResource(R.string.back_translation_loading)
                    } else {
                        text
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VietnamesePronunciationLine(pronunciation: String) {
    Spacer(modifier = Modifier.height(6.dp))
    SelectionContainer {
        Text(
            text = stringResource(R.string.vietnamese_pronunciation_inline, pronunciation),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 27.sp,
        )
    }
}

@Composable
private fun CurrencyConversionCard(
    conversion: CurrencyConversionUi,
    selectedCurrencies: Set<ConversionCurrency>,
) {
    val amountText = "${formatNumberForDisplay(conversion.amountVnd.toString())} ₫"
    val headline = when (conversion.state) {
        CurrencyConversionState.AVAILABLE -> amountText + "  ≈  " +
            formatSelectedCurrencyEstimates(selectedCurrencies) { currency ->
                conversion.minorUnits(currency)
            }
        CurrencyConversionState.LOADING,
        CurrencyConversionState.UNAVAILABLE,
        -> amountText
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = headline,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TextFieldActionHeader(
    title: String,
    onSpeak: () -> Unit,
    speakEnabled: Boolean,
    speakDescription: String,
    onNumericInput: (() -> Unit)? = null,
    numericInputEnabled: Boolean = false,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        onNumericInput?.let { onClick ->
            OutlinedButton(
                onClick = onClick,
                enabled = numericInputEnabled,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.semantics {
                    contentDescription = "Quick number entry"
                },
            ) {
                Text("123", fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(
            onClick = onSpeak,
            enabled = speakEnabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.semantics { contentDescription = speakDescription },
        ) {
            Text("🔊", fontSize = 20.sp)
        }
        onSettings?.let { onClick ->
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Open settings" },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = "⚙",
                    fontSize = 23.sp,
                )
            }
        }
    }
}

private fun String.isNumericAmount(): Boolean =
    isNotBlank() && all { it.isDigit() || it.isWhitespace() }

internal fun translationFontSizeSp(text: String): Int {
    val visualDensityScore = text.trim().length + text.count { it == '\n' } * 24
    return when {
        visualDensityScore <= 60 -> 32
        visualDensityScore <= 120 -> 29
        visualDensityScore <= 190 -> 26
        visualDensityScore <= 270 -> 23
        else -> 20
    }
}


private fun directionLabel(direction: SpeechDirection): String =
    "${direction.sourceLanguage.englishDisplayName} → " +
        direction.targetLanguage.englishDisplayName

@Composable
private fun PushToTalkButton(
    direction: SpeechDirection,
    directionLabel: String,
    idleLabel: String,
    listeningLabel: String,
    processingLabel: String,
    primaryStyle: Boolean,
    state: VoiceBridgeUiState,
    hasMicrophonePermission: Boolean,
    onPressStarted: (SpeechDirection) -> Unit,
    onPressReleased: (SpeechDirection) -> Unit,
    onPermissionRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActiveDirection = state.activeSpeechDirection == direction
    val enabled =
        !state.isSpeechPreparing &&
            !state.isProcessing &&
            (!state.isListening || isActiveDirection)
    val buttonColor = when {
        !hasMicrophonePermission -> MaterialTheme.colorScheme.tertiary
        state.isListening && isActiveDirection -> MaterialTheme.colorScheme.error
        state.isProcessing && isActiveDirection -> MaterialTheme.colorScheme.secondary
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        primaryStyle -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val contentColor = when {
        !hasMicrophonePermission -> MaterialTheme.colorScheme.onTertiary
        state.isListening && isActiveDirection -> MaterialTheme.colorScheme.onError
        state.isProcessing && isActiveDirection -> MaterialTheme.colorScheme.onSecondary
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        primaryStyle -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onTertiary
    }
    val label = when {
        state.isListening && isActiveDirection -> listeningLabel
        state.isProcessing && isActiveDirection -> processingLabel
        else -> idleLabel
    }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHasMicrophonePermission by rememberUpdatedState(hasMicrophonePermission)
    val currentDirection by rememberUpdatedState(direction)
    val currentOnPressStarted by rememberUpdatedState(onPressStarted)
    val currentOnPressReleased by rememberUpdatedState(onPressReleased)
    val currentOnPermissionRequired by rememberUpdatedState(onPermissionRequired)

    Surface(
        modifier = modifier
            .height(156.dp)
            .semantics {
                role = Role.Button
                contentDescription = "$directionLabel. ${label.replace('\n', ' ')}"
                onClick(
                    label = when {
                        !hasMicrophonePermission -> "Allow microphone"
                        state.isListening && isActiveDirection -> "Stop recording"
                        else -> "Start recording"
                    },
                ) {
                    when {
                        !enabled -> Unit
                        !hasMicrophonePermission -> onPermissionRequired()
                        state.isListening && isActiveDirection -> onPressReleased(direction)
                        else -> onPressStarted(direction)
                    }
                    enabled
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val pressedDirection = currentDirection
                    val shouldStart = currentEnabled && currentHasMicrophonePermission

                    if (currentEnabled) {
                        down.consume()
                        if (shouldStart) {
                            currentOnPressStarted(pressedDirection)
                        } else {
                            currentOnPermissionRequired()
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activePointer = event.changes.firstOrNull { it.id == down.id }

                        if (shouldStart) {
                            // Claim the complete hold so the surrounding vertical scroll
                            // cannot cancel recording when the finger moves.
                            event.changes.forEach { it.consume() }
                        }

                        if (activePointer == null || !activePointer.pressed) {
                            val action = event.motionEvent?.actionMasked
                            val isPhysicalRelease =
                                action == MotionEvent.ACTION_UP ||
                                    action == MotionEvent.ACTION_POINTER_UP
                            if (shouldStart && isPhysicalRelease) {
                                currentOnPressReleased(pressedDirection)
                            }
                            break
                        }
                    }
                }
            },
        shape = RoundedCornerShape(28.dp),
        color = buttonColor,
        tonalElevation = 8.dp,
        shadowElevation = 7.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = directionLabel,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 23.sp,
                )
            }
        }
    }
}
