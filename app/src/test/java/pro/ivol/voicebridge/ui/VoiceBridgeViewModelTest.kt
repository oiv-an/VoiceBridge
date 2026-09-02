package pro.ivol.voicebridge.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
import pro.ivol.voicebridge.exchange.VndRubRate
import pro.ivol.voicebridge.exchange.VndRubRateProvider
import pro.ivol.voicebridge.exchange.VndRubRateSource
import java.math.BigDecimal
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBridgeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `currency selection is restored toggled and persisted`() {
        val store = FakeCurrencySelectionStore(
            CurrencySelection(setOf(ConversionCurrency.EUR, ConversionCurrency.UAH)),
        )
        val viewModel = VoiceBridgeViewModel(
            speechEngine = FakeSpeechEngine(),
            translationEngine = FakeTranslationEngine(""),
            currencySelectionStore = store,
        )

        assertEquals(
            setOf(ConversionCurrency.EUR, ConversionCurrency.UAH),
            viewModel.uiState.value.selectedCurrencies,
        )

        viewModel.toggleCurrency(ConversionCurrency.RUB)

        assertEquals(
            setOf(ConversionCurrency.RUB, ConversionCurrency.EUR, ConversionCurrency.UAH),
            viewModel.uiState.value.selectedCurrencies,
        )
        assertEquals(viewModel.uiState.value.selectedCurrencies, store.lastSaved?.currencies)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `last selected currency cannot be removed or redundantly saved`() {
        val store = FakeCurrencySelectionStore(
            CurrencySelection(setOf(ConversionCurrency.RUB)),
        )
        val viewModel = VoiceBridgeViewModel(
            speechEngine = FakeSpeechEngine(),
            translationEngine = FakeTranslationEngine(""),
            currencySelectionStore = store,
        )

        viewModel.toggleCurrency(ConversionCurrency.RUB)

        assertEquals(setOf(ConversionCurrency.RUB), viewModel.uiState.value.selectedCurrencies)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `final speech result is translated with selected languages`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = FakeTranslationEngine("xin chào")
            val viewModel = VoiceBridgeViewModel(speechEngine, translationEngine)

            viewModel.onPushToTalkPressed()
            speechEngine.emit(SpeechEvent.FinalResult("привет"))
            advanceUntilIdle()

            assertEquals("привет", viewModel.uiState.value.transcript)
            assertEquals("xin chào", viewModel.uiState.value.translation)
            assertEquals(AppLanguage.RUSSIAN, translationEngine.lastRequest?.sourceLanguage)
            assertEquals(AppLanguage.VIETNAMESE, translationEngine.lastRequest?.targetLanguage)
        }

    @Test
    fun `completed non-russian translation is checked back into russian`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = FakeTranslationEngine("Xin chào")
            val backTranslationEngine = FakeTranslationEngine("Привет")
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = translationEngine,
                backTranslationEngine = backTranslationEngine,
            )

            viewModel.onPushToTalkPressed()
            speechEngine.emit(SpeechEvent.FinalResult("Привет"))
            advanceUntilIdle()

            assertEquals("Xin chào", viewModel.uiState.value.translation)
            assertEquals("Привет", viewModel.uiState.value.backTranslation)
            assertEquals("Xin chào.", backTranslationEngine.lastRequest?.text)
            assertEquals(
                AppLanguage.VIETNAMESE,
                backTranslationEngine.lastRequest?.sourceLanguage,
            )
            assertEquals(
                AppLanguage.RUSSIAN,
                backTranslationEngine.lastRequest?.targetLanguage,
            )
        }

    @Test
    fun `missing terminal punctuation is added for russian decoding`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val backTranslationEngine = RoutingTranslationEngine { request ->
                if (
                    request.sourceLanguage == AppLanguage.VIETNAMESE &&
                    request.targetLanguage == AppLanguage.RUSSIAN &&
                    request.text == "Vì vậy, kiểm tra văn bản nhận dạng."
                ) {
                    "Так что проверьте распознанный текст."
                } else {
                    "So check identity documents"
                }
            }
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(
                    "Vì vậy, kiểm tra văn bản nhận dạng",
                ),
                backTranslationEngine = backTranslationEngine,
            )

            viewModel.onTranscriptChanged("Так проверка распознавания текста")
            advanceUntilIdle()

            assertEquals(
                "Так что проверьте распознанный текст.",
                viewModel.uiState.value.backTranslation,
            )
            assertEquals(1, backTranslationEngine.requests.size)
            assertEquals(
                "Vì vậy, kiểm tra văn bản nhận dạng.",
                backTranslationEngine.requests.single().text,
            )
        }

    @Test
    fun `translation already targeting russian is not checked twice`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val backTranslationEngine = FakeTranslationEngine("unused")
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine("Привет"),
                backTranslationEngine = backTranslationEngine,
            )

            viewModel.onPushToTalkPressed(
                SpeechDirection(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
            )
            speechEngine.emit(SpeechEvent.FinalResult("Xin chào"))
            advanceUntilIdle()

            assertEquals("Привет", viewModel.uiState.value.translation)
            assertEquals("", viewModel.uiState.value.backTranslation)
            assertEquals(null, backTranslationEngine.lastRequest)
        }

    @Test
    fun `english intermediate output is repaired into russian`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val backTranslationEngine = RoutingTranslationEngine { request ->
                when (request.sourceLanguage to request.targetLanguage) {
                    AppLanguage.VIETNAMESE to AppLanguage.RUSSIAN ->
                        "So check identity documents"
                    AppLanguage.VIETNAMESE to AppLanguage.ENGLISH ->
                        "So check the recognized text"
                    AppLanguage.ENGLISH to AppLanguage.RUSSIAN ->
                        "Итак, проверьте распознанный текст"
                    else -> error("Unexpected language pair")
                }
            }
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(
                    "Vì vậy, kiểm tra văn bản nhận dạng",
                ),
                backTranslationEngine = backTranslationEngine,
            )

            viewModel.onTranscriptChanged("Так проверка распознавания текста")
            advanceUntilIdle()

            assertEquals(
                "Итак, проверьте распознанный текст",
                viewModel.uiState.value.backTranslation,
            )
            assertEquals(
                listOf(
                    AppLanguage.VIETNAMESE to AppLanguage.RUSSIAN,
                    AppLanguage.VIETNAMESE to AppLanguage.ENGLISH,
                    AppLanguage.ENGLISH to AppLanguage.RUSSIAN,
                ),
                backTranslationEngine.requests.map {
                    it.sourceLanguage to it.targetLanguage
                },
            )
        }

    @Test
    fun `main translation is visible while russian check is still running`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val backTranslationEngine = ControllableTranslationEngine()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("Xin chào"),
                backTranslationEngine = backTranslationEngine,
            )

            viewModel.onTranscriptChanged("Привет")
            advanceTimeBy(350)
            runCurrent()

            assertEquals("Xin chào", viewModel.uiState.value.translation)
            assertEquals("", viewModel.uiState.value.backTranslation)
            assertTrue(viewModel.uiState.value.isBackTranslating)

            backTranslationEngine.complete("Привет")
            runCurrent()

            assertEquals("Привет", viewModel.uiState.value.backTranslation)
            assertTrue(!viewModel.uiState.value.isBackTranslating)
        }

    @Test
    fun `edited transcript is translated after debounce`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = FakeTranslationEngine("hello")
            val viewModel = VoiceBridgeViewModel(speechEngine, translationEngine)

            viewModel.selectTargetLanguage(AppLanguage.ENGLISH)
            viewModel.onTranscriptChanged("привет")
            advanceUntilIdle()

            assertEquals("hello", viewModel.uiState.value.translation)
            assertEquals("привет", translationEngine.lastRequest?.text)
        }

    @Test
    fun `release stops speech engine`() = runTest(mainDispatcherRule.testDispatcher) {
        val speechEngine = FakeSpeechEngine()
        val viewModel = VoiceBridgeViewModel(
            speechEngine = speechEngine,
            translationEngine = FakeTranslationEngine(""),
        )

        viewModel.onPushToTalkPressed()
        viewModel.onPushToTalkReleased()

        assertTrue(speechEngine.stopCalled)
        assertTrue(viewModel.uiState.value.isProcessing)
    }

    @Test
    fun `local speech mode can be selected without changing conversation text`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
            )

            viewModel.onTranscriptChanged("сохранить эту фразу")
            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)

            assertEquals(SpeechRecognitionMode.NVIDIA_LOCAL, speechEngine.selectedMode)
            assertEquals(
                SpeechRecognitionMode.NVIDIA_LOCAL,
                viewModel.uiState.value.speechRecognitionMode,
            )
            assertEquals("NVIDIA Nemotron 3.5 (локально)", viewModel.uiState.value.speechBackend)
            assertEquals("сохранить эту фразу", viewModel.uiState.value.transcript)
        }

    @Test
    fun `vietnamese button atomically starts vietnamese to russian turn`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = FakeTranslationEngine("привет")
            val viewModel = VoiceBridgeViewModel(speechEngine, translationEngine)
            val direction = SpeechDirection(
                sourceLanguage = AppLanguage.VIETNAMESE,
                targetLanguage = AppLanguage.RUSSIAN,
            )

            viewModel.onPushToTalkPressed(direction)

            assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.sourceLanguage)
            assertEquals(AppLanguage.RUSSIAN, viewModel.uiState.value.targetLanguage)
            assertEquals("vi-VN", speechEngine.lastLanguageTag)

            speechEngine.emit(SpeechEvent.FinalResult("xin chào"))
            advanceUntilIdle()

            assertEquals(AppLanguage.VIETNAMESE, translationEngine.lastRequest?.sourceLanguage)
            assertEquals(AppLanguage.RUSSIAN, translationEngine.lastRequest?.targetLanguage)
        }

    @Test
    fun `release from second button does not stop first button session`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
            )
            val russianButton = SpeechDirection(
                sourceLanguage = AppLanguage.RUSSIAN,
                targetLanguage = AppLanguage.VIETNAMESE,
            )
            val vietnameseButton = SpeechDirection(
                sourceLanguage = AppLanguage.VIETNAMESE,
                targetLanguage = AppLanguage.RUSSIAN,
            )

            viewModel.onPushToTalkPressed(russianButton)
            viewModel.onPushToTalkPressed(vietnameseButton)
            viewModel.onPushToTalkReleased(vietnameseButton)

            assertTrue(viewModel.uiState.value.isListening)
            assertTrue(!speechEngine.stopCalled)

            viewModel.onPushToTalkReleased(russianButton)

            assertTrue(speechEngine.stopCalled)
            assertTrue(viewModel.uiState.value.isProcessing)
        }

    @Test
    fun `language changes are ignored while recognition is active`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
            )

            viewModel.onPushToTalkPressed()
            viewModel.selectSourceLanguage(AppLanguage.ENGLISH)
            viewModel.swapLanguages()

            assertEquals(AppLanguage.RUSSIAN, viewModel.uiState.value.sourceLanguage)
            assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.targetLanguage)
        }

    @Test
    fun `saved language pair is restored and used by the primary talk action`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val languagePairStore = FakeLanguagePairStore(
                LanguagePair(AppLanguage.ENGLISH, AppLanguage.VIETNAMESE),
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                languagePairStore = languagePairStore,
            )

            assertEquals(AppLanguage.ENGLISH, viewModel.uiState.value.configuredSourceLanguage)
            assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.configuredTargetLanguage)

            viewModel.onPushToTalkPressed()

            assertEquals("en-US", speechEngine.lastLanguageTag)
            assertEquals(0, languagePairStore.saveCount)
        }

    @Test
    fun `reverse talk direction does not overwrite configured language pair`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val languagePairStore = FakeLanguagePairStore(
                LanguagePair(AppLanguage.RUSSIAN, AppLanguage.VIETNAMESE),
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                languagePairStore = languagePairStore,
            )

            viewModel.onPushToTalkPressed(
                SpeechDirection(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
            )

            assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.sourceLanguage)
            assertEquals(AppLanguage.RUSSIAN, viewModel.uiState.value.targetLanguage)
            assertEquals(AppLanguage.RUSSIAN, viewModel.uiState.value.configuredSourceLanguage)
            assertEquals(AppLanguage.VIETNAMESE, viewModel.uiState.value.configuredTargetLanguage)
            assertEquals(0, languagePairStore.saveCount)
        }

    @Test
    fun `settings language changes are persisted`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val languagePairStore = FakeLanguagePairStore(
                LanguagePair(AppLanguage.RUSSIAN, AppLanguage.VIETNAMESE),
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                languagePairStore = languagePairStore,
            )

            viewModel.selectTargetLanguage(AppLanguage.ENGLISH)

            assertEquals(
                LanguagePair(AppLanguage.RUSSIAN, AppLanguage.ENGLISH),
                languagePairStore.lastSaved,
            )

            viewModel.swapLanguages()

            assertEquals(
                LanguagePair(AppLanguage.ENGLISH, AppLanguage.RUSSIAN),
                languagePairStore.lastSaved,
            )
            assertEquals(2, languagePairStore.saveCount)
        }

    @Test
    fun `speech error wins over a late translation result`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = ControllableTranslationEngine()
            val viewModel = VoiceBridgeViewModel(speechEngine, translationEngine)

            viewModel.onPushToTalkPressed()
            speechEngine.emit(SpeechEvent.PartialResult("привет"))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isTranslating)

            speechEngine.emit(SpeechEvent.Error("микрофон отключён"))
            translationEngine.complete("xin chào")
            runCurrent()

            assertEquals("", viewModel.uiState.value.translation)
            assertEquals("микрофон отключён", viewModel.uiState.value.error)
            assertTrue(!viewModel.uiState.value.isTranslating)
        }

    @Test
    fun `blank edit cancels translation and clears busy state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = ControllableTranslationEngine()
            val viewModel = VoiceBridgeViewModel(speechEngine, translationEngine)

            viewModel.onTranscriptChanged("привет")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isTranslating)

            viewModel.onTranscriptChanged("")
            translationEngine.complete("hello")
            runCurrent()

            assertEquals("", viewModel.uiState.value.transcript)
            assertEquals("", viewModel.uiState.value.translation)
            assertTrue(!viewModel.uiState.value.isTranslating)
        }

    @Test
    fun `host stop cancels the active microphone session`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
            )

            viewModel.onPushToTalkPressed()
            viewModel.onHostStopped()

            assertTrue(speechEngine.cancelCalled)
            assertTrue(!viewModel.uiState.value.isListening)
            assertTrue(!viewModel.uiState.value.isProcessing)
        }

    @Test
    fun `host stop clears active text to speech state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speaker = FakeTextSpeaker()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                textSpeaker = speaker,
            )

            viewModel.onTranscriptChanged("привет")
            viewModel.speakTranscript()
            assertTrue(viewModel.uiState.value.isSpeaking)

            viewModel.onHostStopped()

            assertTrue(speaker.stopCalled)
            assertTrue(!viewModel.uiState.value.isSpeaking)
        }

    @Test
    fun `translation speaker uses the target language`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speaker = FakeTextSpeaker()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(
                    localModelInstalled = false,
                    localModeAvailable = false,
                ),
                translationEngine = FakeTranslationEngine("xin chào"),
                textSpeaker = speaker,
            )

            viewModel.onTranscriptChanged("привет")
            advanceUntilIdle()
            viewModel.speakTranslation()

            assertEquals("xin chào", speaker.lastText)
            assertEquals(AppLanguage.VIETNAMESE, speaker.lastLanguage)
            assertTrue(viewModel.uiState.value.isSpeaking)

            speaker.finish()
            assertTrue(!viewModel.uiState.value.isSpeaking)
        }

    @Test
    fun `large-number display formatting does not change text sent to speech`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speaker = FakeTextSpeaker()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("mười lăm triệu"),
                textSpeaker = speaker,
            )

            viewModel.onTranscriptChanged("15000000")
            advanceUntilIdle()
            viewModel.speakTranscript()

            assertEquals("15000000", viewModel.uiState.value.transcript)
            assertEquals("15000000", speaker.lastText)
        }

    @Test
    fun `one Local tap installs the complete bundle and enables local mode`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val installer = FakeLocalModelInstaller()
            val speechEngine = FakeSpeechEngine(
                localModelInstalled = true,
                localModeAvailable = true,
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = installer,
            )

            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            advanceUntilIdle()

            assertEquals(1, installer.installCount)
            assertTrue(viewModel.uiState.value.localModelInstalled)
            assertTrue(viewModel.uiState.value.localSpeechAvailable)
            assertEquals(1f, viewModel.uiState.value.modelDownloadProgress)
            assertEquals(
                SpeechRecognitionMode.NVIDIA_LOCAL,
                viewModel.uiState.value.speechRecognitionMode,
            )
            assertEquals(SpeechRecognitionMode.NVIDIA_LOCAL, speechEngine.selectedMode)
        }

    @Test
    fun `failed offline bundle never activates local mode`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val installer = FailingLocalModelInstaller()
            val speechEngine = FakeSpeechEngine(
                localModelInstalled = true,
                localModeAvailable = true,
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = installer,
            )

            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            advanceUntilIdle()

            assertEquals(SpeechRecognitionMode.ONLINE, speechEngine.selectedMode)
            assertTrue(SpeechRecognitionMode.NVIDIA_LOCAL !in speechEngine.selectionCalls)
            assertTrue(!viewModel.uiState.value.isModelDownloading)
            assertEquals("translation model unavailable", viewModel.uiState.value.error)
        }

    @Test
    fun `failed validation clears a stale installed marker`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val installer = FailingLocalModelInstaller(initiallyInstalled = true)
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = installer,
            )
            assertTrue(viewModel.uiState.value.localModelInstalled)

            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            advanceUntilIdle()

            assertTrue(!viewModel.uiState.value.localModelInstalled)
            assertTrue(!viewModel.uiState.value.localSpeechAvailable)
            assertEquals("translation model unavailable", viewModel.uiState.value.error)
        }

    @Test
    fun `choosing Online during download prevents late Local activation`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val installer = PausingLocalModelInstaller()
            val speechEngine = FakeSpeechEngine(
                localModelInstalled = true,
                localModeAvailable = true,
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = installer,
            )

            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            installer.started.await()
            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.ONLINE)
            installer.finish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(SpeechRecognitionMode.ONLINE), speechEngine.selectionCalls)
            assertEquals(SpeechRecognitionMode.ONLINE, viewModel.uiState.value.speechRecognitionMode)
            assertTrue(viewModel.uiState.value.localModelInstalled)
        }

    @Test
    fun `restart immediately after cancel keeps one active installer job`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val installer = PausingLocalModelInstaller()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = installer,
            )

            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            installer.started.await()
            viewModel.cancelLocalModelDownload()
            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            runCurrent()
            viewModel.selectSpeechRecognitionMode(SpeechRecognitionMode.NVIDIA_LOCAL)
            runCurrent()

            assertEquals(2, installer.installCount)

            installer.finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                SpeechRecognitionMode.NVIDIA_LOCAL,
                viewModel.uiState.value.speechRecognitionMode,
            )
        }

    @Test
    fun `screen model download uses only translation installer and leaves speech online`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val fullBundleInstaller = FakeLocalModelInstaller()
            val screenInstaller = FakeLocalModelInstaller()
            val speechEngine = FakeSpeechEngine(
                localModelInstalled = true,
                localModeAvailable = true,
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = fullBundleInstaller,
                screenTranslationModelInstaller = screenInstaller,
            )

            viewModel.downloadOfflineModelsForScreenTranslation()
            advanceUntilIdle()

            assertEquals(0, fullBundleInstaller.installCount)
            assertEquals(1, screenInstaller.installCount)
            assertEquals(SpeechRecognitionMode.ONLINE, viewModel.uiState.value.speechRecognitionMode)
            assertTrue(speechEngine.selectionCalls.isEmpty())
            assertTrue(!viewModel.uiState.value.localModelInstalled)
            assertTrue(!viewModel.uiState.value.isModelDownloading)
            assertEquals(null, viewModel.uiState.value.modelDownloadProgress)
            assertTrue(!viewModel.uiState.value.isScreenModelDownloading)
            assertEquals(1f, viewModel.uiState.value.screenModelDownloadProgress)
            assertEquals(
                "Offline screen translation is ready.",
                viewModel.uiState.value.screenModelDownloadStatus,
            )
        }

    @Test
    fun `screen model failure preserves full bundle state and does not activate speech`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val fullBundleInstaller = FailingLocalModelInstaller(initiallyInstalled = true)
            val screenInstaller = FailingLocalModelInstaller()
            val speechEngine = FakeSpeechEngine(
                localModelInstalled = true,
                localModeAvailable = true,
            )
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = fullBundleInstaller,
                screenTranslationModelInstaller = screenInstaller,
            )

            viewModel.downloadOfflineModelsForScreenTranslation()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.localModelInstalled)
            assertTrue(viewModel.uiState.value.localSpeechAvailable)
            assertEquals(SpeechRecognitionMode.ONLINE, viewModel.uiState.value.speechRecognitionMode)
            assertTrue(speechEngine.selectionCalls.isEmpty())
            assertTrue(!viewModel.uiState.value.isModelDownloading)
            assertTrue(!viewModel.uiState.value.isScreenModelDownloading)
            assertEquals(
                "translation model unavailable",
                viewModel.uiState.value.screenModelDownloadStatus,
            )
            assertEquals(null, viewModel.uiState.value.error)
        }

    @Test
    fun `cancel routes to active screen installer without changing full bundle fields`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val fullBundleInstaller = FakeLocalModelInstaller()
            val screenInstaller = PausingLocalModelInstaller()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine(""),
                localModelInstaller = fullBundleInstaller,
                screenTranslationModelInstaller = screenInstaller,
            )

            viewModel.downloadOfflineModelsForScreenTranslation()
            screenInstaller.started.await()
            assertTrue(viewModel.uiState.value.isScreenModelDownloading)

            viewModel.cancelLocalModelDownload()
            runCurrent()

            assertEquals(1, screenInstaller.cancelCount)
            assertEquals(0, fullBundleInstaller.installCount)
            assertTrue(!viewModel.uiState.value.isModelDownloading)
            assertEquals(null, viewModel.uiState.value.modelDownloadStatus)
            assertTrue(!viewModel.uiState.value.isScreenModelDownloading)
            assertEquals(null, viewModel.uiState.value.screenModelDownloadProgress)
            assertEquals(
                "Download paused. You can resume it later.",
                viewModel.uiState.value.screenModelDownloadStatus,
            )
        }

    @Test
    fun `vietnamese price is converted to roubles without changing translation`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val rateProvider = FakeVndRubRateProvider()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = FakeTranslationEngine("полтора миллиона донгов"),
                vndRubRateProvider = rateProvider,
            )

            viewModel.onPushToTalkPressed(
                SpeechDirection(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
            )
            speechEngine.emit(SpeechEvent.FinalResult("1,5 triệu đồng"))
            advanceUntilIdle()

            val conversion = viewModel.uiState.value.currencyConversions.singleOrNull()
            assertEquals("полтора миллиона донгов", viewModel.uiState.value.translation)
            assertEquals(1_500_000L, conversion?.amountVnd)
            assertEquals(5_102L, conversion?.estimatedRubles)
            assertEquals(6_152L, conversion?.estimatedUsdCents)
            assertEquals(5, conversion?.exchangeBufferPercent)
            assertEquals("23.08.2026", conversion?.cbrEffectiveDate)
            assertEquals(1, rateProvider.requestCount)
        }

    @Test
    fun `standalone amount from keypad is converted in both language directions`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val rateProvider = FakeVndRubRateProvider()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("один миллион"),
                languagePairStore = FakeLanguagePairStore(
                    LanguagePair(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
                ),
                vndRubRateProvider = rateProvider,
            )

            viewModel.onTranscriptChanged("1 000 000")
            advanceUntilIdle()

            assertEquals(
                1_000_000L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.amountVnd,
            )
            assertEquals(
                3_401L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.estimatedRubles,
            )
            assertEquals(
                4_102L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.estimatedUsdCents,
            )

            viewModel.swapLanguages()
            viewModel.onTranscriptChanged("2 000 000")
            advanceUntilIdle()

            assertEquals(
                2_000_000L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.amountVnd,
            )
            assertEquals(2, rateProvider.requestCount)
        }

    @Test
    fun `rate failure keeps the detected amount visible and translation still succeeds`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("миллион донгов"),
                languagePairStore = FakeLanguagePairStore(
                    LanguagePair(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
                ),
                vndRubRateProvider = FakeVndRubRateProvider(
                    failure = IllegalStateException("offline"),
                ),
            )

            viewModel.onTranscriptChanged("một triệu đồng")
            assertEquals(
                CurrencyConversionState.LOADING,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.state,
            )
            assertEquals(
                1_000_000L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.amountVnd,
            )
            advanceUntilIdle()

            assertEquals("миллион донгов", viewModel.uiState.value.translation)
            assertEquals(
                CurrencyConversionState.UNAVAILABLE,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.state,
            )
            assertEquals(null, viewModel.uiState.value.error)
        }

    @Test
    fun `verified cached rate is shown before a failed network refresh`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cachedRate = testVndRubRate(stale = true)
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("five million"),
                vndRubRateProvider = FakeVndRubRateProvider(
                    failure = IllegalStateException("offline"),
                    immediateCachedRate = cachedRate,
                ),
            )

            viewModel.onTranscriptChanged("5000000")

            val immediate = viewModel.uiState.value.currencyConversions.singleOrNull()
            assertEquals(CurrencyConversionState.AVAILABLE, immediate?.state)
            assertEquals(5_000_000L, immediate?.amountVnd)
            assertEquals(17_005L, immediate?.estimatedRubles)
            assertEquals(20_508L, immediate?.estimatedUsdCents)
            assertEquals(true, immediate?.isStale)

            advanceUntilIdle()
            assertEquals(immediate, viewModel.uiState.value.currencyConversions.singleOrNull())
        }

    @Test
    fun `quick amount calculator uses cached quote and the same five percent buffer`() {
        val rateProvider = FakeVndRubRateProvider(
            immediateCachedRate = testVndRubRate(stale = true),
        )
        val viewModel = VoiceBridgeViewModel(
            speechEngine = FakeSpeechEngine(),
            translationEngine = FakeTranslationEngine(""),
            vndRubRateProvider = rateProvider,
        )

        assertEquals(
            QuickCurrencyEstimateUi(
                estimatedRubles = 17_005L,
                estimatedUsdCents = 20_508L,
                estimatedEurCents = 20_508L,
                estimatedUahKopecks = 20_508L,
            ),
            viewModel.estimateCurrenciesForVnd(5_000_000L),
        )
        assertEquals(
            QuickCurrencyEstimateUi(
                estimatedRubles = 0L,
                estimatedUsdCents = 0L,
                estimatedEurCents = 0L,
                estimatedUahKopecks = 0L,
            ),
            viewModel.estimateCurrenciesForVnd(0L),
        )
        assertEquals(0, rateProvider.requestCount)
    }

    @Test
    fun `late rate response from an older transcript cannot replace the new amount`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val rateProvider = ControllableVndRubRateProvider()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = FakeSpeechEngine(),
                translationEngine = FakeTranslationEngine("цена"),
                languagePairStore = FakeLanguagePairStore(
                    LanguagePair(AppLanguage.VIETNAMESE, AppLanguage.RUSSIAN),
                ),
                vndRubRateProvider = rateProvider,
            )

            viewModel.onTranscriptChanged("1 triệu")
            advanceTimeBy(350)
            runCurrent()
            assertEquals(1, rateProvider.requestCount)

            viewModel.onTranscriptChanged("2 triệu")
            advanceTimeBy(350)
            runCurrent()
            assertEquals(2, rateProvider.requestCount)

            rateProvider.complete(requestIndex = 0)
            runCurrent()
            assertEquals(
                2_000_000L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.amountVnd,
            )
            assertEquals(
                CurrencyConversionState.LOADING,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.state,
            )

            rateProvider.complete(requestIndex = 1)
            advanceUntilIdle()
            assertEquals(
                2_000_000L,
                viewModel.uiState.value.currencyConversions.singleOrNull()?.amountVnd,
            )
        }

    @Test
    fun `partial speech keeps raw text and converts every amount immediately`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val speechEngine = FakeSpeechEngine()
            val translationEngine = FakeTranslationEngine("giá")
            val rateProvider = FakeVndRubRateProvider()
            val viewModel = VoiceBridgeViewModel(
                speechEngine = speechEngine,
                translationEngine = translationEngine,
                vndRubRateProvider = rateProvider,
            )

            viewModel.onPushToTalkPressed(
                SpeechDirection(AppLanguage.RUSSIAN, AppLanguage.VIETNAMESE),
            )
            speechEngine.emit(
                SpeechEvent.PartialResult("сначала 15000000, потом 250000 донгов"),
            )
            runCurrent()

            assertEquals(
                "сначала 15000000, потом 250000 донгов",
                viewModel.uiState.value.transcript,
            )
            assertEquals(
                listOf(15_000_000L, 250_000L),
                viewModel.uiState.value.currencyConversions.map(CurrencyConversionUi::amountVnd),
            )
            assertEquals(1, rateProvider.requestCount)
            assertEquals(null, translationEngine.lastRequest)

            advanceTimeBy(450)
            runCurrent()
            assertEquals(
                "сначала 15000000, потом 250000 донгов",
                translationEngine.lastRequest?.text,
            )
        }

    private class FakeSpeechEngine(
        override val localModelInstalled: Boolean = true,
        override val localModeAvailable: Boolean = true,
    ) : SpeechEngine, SpeechModeController {
        override var displayName = "Fake speech"
            private set

        override var selectedMode = SpeechRecognitionMode.ONLINE
            private set

        private var listener: ((SpeechEvent) -> Unit)? = null
        var stopCalled = false
        var cancelCalled = false
        var lastLanguageTag: String? = null
        val selectionCalls = mutableListOf<SpeechRecognitionMode>()

        override fun setListener(listener: (SpeechEvent) -> Unit) {
            this.listener = listener
        }

        override fun startListening(languageTag: String) {
            lastLanguageTag = languageTag
        }

        override fun stopListening() {
            stopCalled = true
        }

        override fun cancelListening() {
            cancelCalled = true
        }

        override fun selectMode(mode: SpeechRecognitionMode): Result<Unit> {
            selectionCalls += mode
            selectedMode = mode
            displayName = when (mode) {
                SpeechRecognitionMode.ONLINE -> "Fake speech"
                SpeechRecognitionMode.NVIDIA_LOCAL -> "NVIDIA Nemotron 3.5 (локально)"
            }
            listener?.invoke(SpeechEvent.ModeChanged(mode, displayName))
            return Result.success(Unit)
        }

        override fun destroy() = Unit

        fun emit(event: SpeechEvent) {
            listener?.invoke(event)
        }
    }

    private class FakeTranslationEngine(
        private val translatedText: String,
    ) : TranslationEngine {
        override val displayName = "Fake translation"

        var lastRequest: TranslationRequest? = null

        override suspend fun translate(request: TranslationRequest): Result<String> {
            lastRequest = request
            return Result.success(translatedText)
        }
    }

    private class RoutingTranslationEngine(
        private val responder: (TranslationRequest) -> String,
    ) : TranslationEngine {
        override val displayName = "Routing translation"

        val requests = mutableListOf<TranslationRequest>()

        override suspend fun translate(request: TranslationRequest): Result<String> {
            requests += request
            return runCatching { responder(request) }
        }
    }

    private class FakeTextSpeaker : TextSpeaker {
        private var listener: ((TextSpeakerEvent) -> Unit)? = null
        private var lastUtteranceId = ""
        var lastText: String? = null
            private set
        var lastLanguage: AppLanguage? = null
            private set
        var stopCalled = false
            private set

        override fun setListener(listener: (TextSpeakerEvent) -> Unit) {
            this.listener = listener
        }

        override fun speak(text: String, language: AppLanguage): String {
            lastText = text
            lastLanguage = language
            lastUtteranceId = "fake-1"
            listener?.invoke(TextSpeakerEvent.Started(lastUtteranceId))
            return lastUtteranceId
        }

        override fun stop() {
            stopCalled = true
        }

        override fun close() = Unit

        fun finish() {
            listener?.invoke(TextSpeakerEvent.Done(lastUtteranceId))
        }
    }

    private class FakeLocalModelInstaller : LocalModelInstaller {
        override var isInstalled = false
            private set
        var installCount = 0
            private set

        override fun install(): Flow<LocalModelInstallEvent> {
            installCount += 1
            isInstalled = true
            return flowOf(
                LocalModelInstallEvent.CheckingStorage(
                    requiredFreeBytes = 1_000,
                    availableFreeBytes = 2_000,
                ),
                LocalModelInstallEvent.Downloading(
                    fileName = "model",
                    fileIndex = 1,
                    fileCount = 1,
                    fileDownloadedBytes = 100,
                    fileSizeBytes = 100,
                    downloadedBytes = 100,
                    totalBytes = 100,
                ),
                LocalModelInstallEvent.Installed(alreadyInstalled = false),
            )
        }

        override fun cancel() = Unit
    }

    private class FailingLocalModelInstaller(
        initiallyInstalled: Boolean = false,
    ) : LocalModelInstaller {
        override var isInstalled = initiallyInstalled
            private set

        override fun install(): Flow<LocalModelInstallEvent> = flow {
            isInstalled = false
            emit(LocalModelInstallEvent.Failed("translation model unavailable"))
        }

        override fun cancel() = Unit
    }

    private class PausingLocalModelInstaller : LocalModelInstaller {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var installCount = 0
            private set
        var cancelCount = 0
            private set

        override var isInstalled = false
            private set

        override fun install(): Flow<LocalModelInstallEvent> = flow {
            installCount += 1
            started.complete(Unit)
            finish.await()
            isInstalled = true
            emit(LocalModelInstallEvent.Installed(alreadyInstalled = false))
        }

        override fun cancel() {
            cancelCount += 1
        }
    }

    private class FakeLanguagePairStore(
        private val loadedPair: LanguagePair,
    ) : LanguagePairStore {
        var lastSaved: LanguagePair? = null
            private set
        var saveCount: Int = 0
            private set

        override fun load(): LanguagePair = loadedPair

        override fun save(languagePair: LanguagePair) {
            lastSaved = languagePair
            saveCount += 1
        }
    }

    private class FakeCurrencySelectionStore(
        private val loadedSelection: CurrencySelection,
    ) : CurrencySelectionStore {
        var lastSaved: CurrencySelection? = null
            private set
        var saveCount: Int = 0
            private set

        override fun load(): CurrencySelection = loadedSelection

        override fun save(selection: CurrencySelection) {
            lastSaved = selection
            saveCount += 1
        }
    }

    private class ControllableTranslationEngine : TranslationEngine {
        override val displayName = "Controllable translation"

        private var continuation: Continuation<Result<String>>? = null

        override suspend fun translate(request: TranslationRequest): Result<String> =
            suspendCoroutine { continuation = it }

        fun complete(text: String) {
            continuation?.also {
                continuation = null
                it.resume(Result.success(text))
            }
        }
    }

    private class FakeVndRubRateProvider(
        private val failure: Throwable? = null,
        private val stale: Boolean = false,
        private val immediateCachedRate: VndRubRate? = null,
    ) : VndRubRateProvider {
        var requestCount = 0
            private set

        override fun cachedRate(): VndRubRate? = immediateCachedRate

        override suspend fun latestRate(): Result<VndRubRate> {
            requestCount += 1
            failure?.let { return Result.failure(it) }
            return Result.success(testVndRubRate(stale))
        }
    }

    private class ControllableVndRubRateProvider : VndRubRateProvider {
        private val requests = mutableListOf<CompletableDeferred<Result<VndRubRate>>>()
        val requestCount: Int
            get() = requests.size

        override suspend fun latestRate(): Result<VndRubRate> {
            val request = CompletableDeferred<Result<VndRubRate>>()
            requests += request
            // Deliberately finish even after caller cancellation to model a
            // blocking network stack and exercise the ViewModel revision guard.
            return withContext(NonCancellable) { request.await() }
        }

        fun complete(requestIndex: Int) {
            requests[requestIndex].complete(
                Result.success(
                    VndRubRate(
                        nominalVnd = BigDecimal("10000"),
                        valueRub = BigDecimal("32.3911"),
                        nominalUsd = BigDecimal("1"),
                        usdValueRub = BigDecimal("82.9211"),
                        nominalEur = BigDecimal("1"),
                        eurValueRub = BigDecimal("82.9211"),
                        nominalUah = BigDecimal("1"),
                        uahValueRub = BigDecimal("82.9211"),
                        cbrEffectiveDate = "23.08.2026",
                        fetchedAtEpochMillis = 1_777_000_000_000L,
                        source = VndRubRateSource.CBR_NETWORK,
                        isStale = false,
                    ),
                ),
            )
        }
    }

    private companion object {
        fun testVndRubRate(stale: Boolean = false) = VndRubRate(
            nominalVnd = BigDecimal("10000"),
            valueRub = BigDecimal("32.3911"),
            nominalUsd = BigDecimal("1"),
            usdValueRub = BigDecimal("82.9211"),
            nominalEur = BigDecimal("1"),
            eurValueRub = BigDecimal("82.9211"),
            nominalUah = BigDecimal("1"),
            uahValueRub = BigDecimal("82.9211"),
            cbrEffectiveDate = "23.08.2026",
            fetchedAtEpochMillis = 1_777_000_000_000L,
            source = if (stale) {
                VndRubRateSource.LAST_KNOWN_CACHE
            } else {
                VndRubRateSource.CBR_NETWORK
            },
            isStale = stale,
        )
    }
}
