package pro.ivol.voicebridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pro.ivol.voicebridge.R
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.ConversionCurrency
import pro.ivol.voicebridge.domain.SpeechRecognitionMode
import pro.ivol.voicebridge.screen.ScreenTranslationServiceState

@Composable
fun VoiceBridgeSettingsDialog(
    sourceLanguage: AppLanguage,
    targetLanguage: AppLanguage,
    selectedCurrencies: Set<ConversionCurrency>,
    selectedMode: SpeechRecognitionMode,
    localModelInstalled: Boolean,
    localModeAvailable: Boolean,
    isModelDownloading: Boolean,
    modelDownloadProgress: Float?,
    modelDownloadStatus: String?,
    isScreenModelDownloading: Boolean,
    screenModelDownloadProgress: Float?,
    screenModelDownloadStatus: String?,
    enabled: Boolean,
    onSourceLanguageSelected: (AppLanguage) -> Unit,
    onTargetLanguageSelected: (AppLanguage) -> Unit,
    onSwapLanguages: () -> Unit,
    onCurrencyToggled: (ConversionCurrency) -> Unit,
    onModeSelected: (SpeechRecognitionMode) -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadScreenModels: () -> Unit,
    onCancelDownload: () -> Unit,
    hasScreenOverlayPermission: Boolean,
    screenAccessibilityConfigured: Boolean,
    screenAccessibilityConnected: Boolean,
    screenTranslationModelsReady: Boolean?,
    screenTranslationModelsError: String?,
    screenTranslationState: ScreenTranslationServiceState,
    screenTranslationError: String?,
    onRequestScreenOverlayPermission: () -> Unit,
    onRequestScreenAccessibility: () -> Unit,
    showRestrictedSettingsHint: Boolean,
    onOpenAppInfo: () -> Unit,
    onRetryScreenModelCheck: () -> Unit,
    onStartScreenTranslation: () -> Unit,
    onStopScreenTranslation: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showPrivacyAndNotices by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    SettingsLanguagePicker(
                        label = stringResource(R.string.source_language),
                        selectedLanguage = sourceLanguage,
                        onLanguageSelected = onSourceLanguageSelected,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = onSwapLanguages,
                        enabled = enabled,
                        modifier = Modifier.height(52.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Text(
                            text = "⇄",
                            fontSize = 25.sp,
                            modifier = Modifier.semantics {
                                contentDescription = "Swap languages"
                            },
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    SettingsLanguagePicker(
                        label = stringResource(R.string.target_language),
                        selectedLanguage = targetLanguage,
                        onLanguageSelected = onTargetLanguageSelected,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.currencies_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.currencies_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ConversionCurrency.entries.chunked(2).forEach { rowCurrencies ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowCurrencies.forEach { currency ->
                            val selected = currency in selectedCurrencies
                            FilterChip(
                                selected = selected,
                                onClick = { onCurrencyToggled(currency) },
                                enabled = enabled && (!selected || selectedCurrencies.size > 1),
                                label = {
                                    Text(
                                        text = "${currency.code} · ${currency.symbol}",
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.speech_mode_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpeechModeButton(
                        text = stringResource(R.string.speech_mode_online),
                        selected = selectedMode == SpeechRecognitionMode.ONLINE,
                        enabled = enabled && !isModelDownloading,
                        onClick = { onModeSelected(SpeechRecognitionMode.ONLINE) },
                        modifier = Modifier.weight(1f),
                    )
                    SpeechModeButton(
                        text = stringResource(R.string.speech_mode_local),
                        selected = selectedMode == SpeechRecognitionMode.NVIDIA_LOCAL,
                        enabled = enabled && !isModelDownloading,
                        onClick = { onModeSelected(SpeechRecognitionMode.NVIDIA_LOCAL) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                LocalModelControls(
                    selectedMode = selectedMode,
                    localModelInstalled = localModelInstalled,
                    localModeAvailable = localModeAvailable,
                    isModelDownloading = isModelDownloading,
                    modelDownloadProgress = modelDownloadProgress,
                    modelDownloadStatus = modelDownloadStatus,
                    enabled = enabled,
                    onDownloadModel = onDownloadModel,
                    onCancelDownload = onCancelDownload,
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(20.dp))

                ScreenTranslationControls(
                    hasOverlayPermission = hasScreenOverlayPermission,
                    accessibilityConfigured = screenAccessibilityConfigured,
                    accessibilityConnected = screenAccessibilityConnected,
                    localModelsReady = screenTranslationModelsReady,
                    modelCheckError = screenTranslationModelsError,
                    isModelDownloading = isScreenModelDownloading,
                    modelDownloadProgress = screenModelDownloadProgress,
                    modelDownloadStatus = screenModelDownloadStatus,
                    serviceState = screenTranslationState,
                    serviceError = screenTranslationError,
                    enabled = enabled,
                    onRequestOverlayPermission = onRequestScreenOverlayPermission,
                    onRequestAccessibility = onRequestScreenAccessibility,
                    showRestrictedSettingsHint = showRestrictedSettingsHint,
                    onOpenAppInfo = onOpenAppInfo,
                    onRetryModelCheck = onRetryScreenModelCheck,
                    onDownloadModels = onDownloadScreenModels,
                    onCancelModels = onCancelDownload,
                    onStart = onStartScreenTranslation,
                    onStop = onStopScreenTranslation,
                )

                Spacer(modifier = Modifier.height(18.dp))
                TextButton(
                    onClick = { showPrivacyAndNotices = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.privacy_notices_button))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }

    if (showPrivacyAndNotices) {
        AlertDialog(
            onDismissRequest = { showPrivacyAndNotices = false },
            title = { Text(stringResource(R.string.privacy_notices_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.privacy_notices_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.google_translation_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyAndNotices = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun ScreenTranslationControls(
    hasOverlayPermission: Boolean,
    accessibilityConfigured: Boolean,
    accessibilityConnected: Boolean,
    localModelsReady: Boolean?,
    modelCheckError: String?,
    isModelDownloading: Boolean,
    modelDownloadProgress: Float?,
    modelDownloadStatus: String?,
    serviceState: ScreenTranslationServiceState,
    serviceError: String?,
    enabled: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    showRestrictedSettingsHint: Boolean,
    onOpenAppInfo: () -> Unit,
    onRetryModelCheck: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModels: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var showAccessibilityConsent by remember { mutableStateOf(false) }
    val serviceActive = serviceState == ScreenTranslationServiceState.STOPPING ||
        serviceState == ScreenTranslationServiceState.STARTING ||
        serviceState == ScreenTranslationServiceState.READY ||
        serviceState == ScreenTranslationServiceState.TRANSLATING ||
        serviceState == ScreenTranslationServiceState.SHOWING
    val allRequirementsReady = hasOverlayPermission &&
        accessibilityConfigured &&
        accessibilityConnected &&
        localModelsReady == true &&
        !isModelDownloading

    Text(
        text = stringResource(R.string.screen_translation_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = stringResource(R.string.screen_translation_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))

    ScreenTranslationRequirementRow(
        label = stringResource(R.string.screen_overlay_permission),
        status = stringResource(
            if (hasOverlayPermission) {
                R.string.screen_permission_ready
            } else {
                R.string.screen_permission_missing
            },
        ),
        ready = hasOverlayPermission,
        actionLabel = if (hasOverlayPermission) null else {
            stringResource(R.string.allow_screen_overlay)
        },
        enabled = enabled,
        onAction = onRequestOverlayPermission,
    )
    ScreenTranslationRequirementRow(
        label = stringResource(R.string.screen_text_access),
        status = stringResource(
            when {
                accessibilityConnected -> R.string.screen_access_connected
                accessibilityConfigured -> R.string.screen_access_connecting
                else -> R.string.screen_access_missing
            },
        ),
        ready = accessibilityConfigured && accessibilityConnected,
        actionLabel = if (accessibilityConfigured && accessibilityConnected) null else {
            stringResource(R.string.enable_screen_text_access)
        },
        enabled = enabled,
        onAction = { showAccessibilityConsent = true },
    )
    if (showRestrictedSettingsHint && !accessibilityConfigured) {
        Text(
            text = stringResource(R.string.screen_restricted_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
            onClick = onOpenAppInfo,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.open_voicebridge_app_info))
        }
        Spacer(modifier = Modifier.height(5.dp))
    }
    ScreenTranslationRequirementRow(
        label = stringResource(R.string.screen_offline_models),
        status = when {
            isModelDownloading -> modelDownloadStatus
                ?: stringResource(R.string.checking_offline_models)
            modelCheckError != null -> modelCheckError
            localModelsReady == null -> stringResource(R.string.checking_offline_models)
            localModelsReady == true -> stringResource(R.string.screen_models_ready)
            else -> stringResource(R.string.screen_models_missing)
        },
        ready = localModelsReady == true && !isModelDownloading,
        actionLabel = when {
            isModelDownloading -> null
            modelCheckError != null -> stringResource(R.string.retry_screen_model_check)
            localModelsReady == false ->
                stringResource(R.string.download_screen_translation_models)
            else -> null
        },
        enabled = enabled,
        onAction = if (modelCheckError != null) onRetryModelCheck else onDownloadModels,
    )
    if (isModelDownloading) {
        Spacer(modifier = Modifier.height(6.dp))
        if (modelDownloadProgress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { modelDownloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancelModels,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cancel_download))
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    when {
        serviceActive -> {
            if (!serviceError.isNullOrBlank()) {
                Text(
                    text = serviceError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(
                    if (serviceState == ScreenTranslationServiceState.STARTING) {
                        R.string.screen_translation_starting
                    } else if (serviceState == ScreenTranslationServiceState.STOPPING) {
                        R.string.screen_translation_stopping
                    } else {
                        R.string.screen_translation_running
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onStop,
                enabled = serviceState != ScreenTranslationServiceState.STOPPING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (serviceState == ScreenTranslationServiceState.STOPPING) {
                            R.string.screen_translation_stopping
                        } else {
                            R.string.stop_screen_translation
                        },
                    ),
                )
            }
        }

        else -> {
            if (!serviceError.isNullOrBlank()) {
                Text(
                    text = serviceError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text = stringResource(R.string.screen_translation_not_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = onStart,
                enabled = enabled && allRequirementsReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start_screen_translation))
            }
        }
    }

    if (showAccessibilityConsent) {
        AlertDialog(
            onDismissRequest = { showAccessibilityConsent = false },
            title = { Text(stringResource(R.string.screen_access_consent_title)) },
            text = { Text(stringResource(R.string.screen_access_consent_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityConsent = false
                        onRequestAccessibility()
                    },
                ) {
                    Text(stringResource(R.string.continue_to_settings))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAccessibilityConsent = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ScreenTranslationRequirementRow(
    label: String,
    status: String,
    ready: Boolean,
    actionLabel: String?,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        actionLabel?.let { buttonLabel ->
            OutlinedButton(
                onClick = onAction,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = buttonLabel,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LocalModelControls(
    selectedMode: SpeechRecognitionMode,
    localModelInstalled: Boolean,
    localModeAvailable: Boolean,
    isModelDownloading: Boolean,
    modelDownloadProgress: Float?,
    modelDownloadStatus: String?,
    enabled: Boolean,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    if (isModelDownloading) {
        Text(
            text = modelDownloadStatus
                ?: stringResource(R.string.checking_offline_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (modelDownloadProgress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { modelDownloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCancelDownload,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cancel_download))
        }
    } else if (!localModelInstalled) {
        Text(
            text = modelDownloadStatus
                ?: stringResource(R.string.speech_mode_not_installed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onDownloadModel,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.download_local_model))
        }
    } else if (!localModeAvailable) {
        Text(
            text = stringResource(R.string.speech_mode_restart_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            text = if (selectedMode == SpeechRecognitionMode.NVIDIA_LOCAL) {
                stringResource(R.string.speech_mode_local_hint)
            } else {
                stringResource(R.string.speech_mode_online_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeechModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(
                text = text,
                maxLines = 2,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(
                text = text,
                maxLines = 2,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingsLanguagePicker(
    label: String,
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(
                    text = selectedLanguage.englishDisplayName,
                    maxLines = 1,
                    fontSize = 13.sp,
                )
            }

            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
            ) {
                AppLanguage.entries.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.englishDisplayName) },
                        onClick = {
                            expanded = false
                            onLanguageSelected(language)
                        },
                    )
                }
            }
        }
    }
}

internal val AppLanguage.englishDisplayName: String
    get() = when (this) {
        AppLanguage.RUSSIAN -> "Russian"
        AppLanguage.ENGLISH -> "English"
        AppLanguage.VIETNAMESE -> "Vietnamese"
    }
