package pro.ivol.voicebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.SpeechRecognitionMode

class VoiceBridgeReducerTest {
    @Test
    fun `press starts a fresh listening turn`() {
        val previous = VoiceBridgeUiState(
            transcript = "старая фраза",
            translation = "old phrase",
            error = "старая ошибка",
        )

        val next = VoiceBridgeReducer.reduce(
            previous,
            VoiceBridgeAction.PressStarted(RUSSIAN_TO_VIETNAMESE),
        )

        assertTrue(next.isListening)
        assertFalse(next.isProcessing)
        assertEquals("", next.transcript)
        assertEquals("", next.translation)
        assertNull(next.error)
    }

    @Test
    fun `speech mode switch updates selected backend`() {
        val next = VoiceBridgeReducer.reduce(
            VoiceBridgeUiState(speechBackend = "Google Speech"),
            VoiceBridgeAction.SpeechModeSelected(
                mode = SpeechRecognitionMode.NVIDIA_LOCAL,
                backendName = "NVIDIA Nemotron 3.5 (локально)",
                localModelInstalled = true,
                localModeAvailable = true,
            ),
        )

        assertEquals(SpeechRecognitionMode.NVIDIA_LOCAL, next.speechRecognitionMode)
        assertEquals("NVIDIA Nemotron 3.5 (локально)", next.speechBackend)
        assertNull(next.error)
    }

    @Test
    fun `partial and final speech results replace transcript`() {
        val listening = VoiceBridgeReducer.reduce(
            VoiceBridgeUiState(),
            VoiceBridgeAction.PressStarted(RUSSIAN_TO_VIETNAMESE),
        )

        val partial = VoiceBridgeReducer.reduce(
            listening,
            VoiceBridgeAction.SpeechPartial("добрый"),
        )
        val final = VoiceBridgeReducer.reduce(
            partial,
            VoiceBridgeAction.SpeechFinal("добрый день"),
        )

        assertEquals("добрый день", final.transcript)
        assertFalse(final.isListening)
        assertFalse(final.isProcessing)
    }

    @Test
    fun `swap also swaps completed text pair`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.ENGLISH,
            transcript = "привет",
            translation = "hello",
            translatedSourceText = "привет",
        )

        val swapped = VoiceBridgeReducer.reduce(
            state,
            VoiceBridgeAction.LanguagesSwapped,
        )

        assertEquals(AppLanguage.ENGLISH, swapped.sourceLanguage)
        assertEquals(AppLanguage.RUSSIAN, swapped.targetLanguage)
        assertEquals("hello", swapped.transcript)
        assertEquals("привет", swapped.translation)
    }

    @Test
    fun `choosing current source as target swaps the language pair`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.VIETNAMESE,
        )

        val next = VoiceBridgeReducer.reduce(
            state,
            VoiceBridgeAction.TargetLanguageSelected(AppLanguage.RUSSIAN),
        )

        assertEquals(AppLanguage.VIETNAMESE, next.sourceLanguage)
        assertEquals(AppLanguage.RUSSIAN, next.targetLanguage)
    }

    @Test
    fun `directional press selects both languages in the same state change`() {
        val next = VoiceBridgeReducer.reduce(
            VoiceBridgeUiState(),
            VoiceBridgeAction.PressStarted(VIETNAMESE_TO_RUSSIAN),
        )

        assertEquals(AppLanguage.VIETNAMESE, next.sourceLanguage)
        assertEquals(AppLanguage.RUSSIAN, next.targetLanguage)
        assertEquals(AppLanguage.RUSSIAN, next.configuredSourceLanguage)
        assertEquals(AppLanguage.VIETNAMESE, next.configuredTargetLanguage)
        assertEquals(VIETNAMESE_TO_RUSSIAN, next.activeSpeechDirection)
        assertTrue(next.isListening)
    }

    @Test
    fun `settings language action updates configured and current direction`() {
        val reverseTurn = VoiceBridgeReducer.reduce(
            VoiceBridgeUiState(),
            VoiceBridgeAction.PressStarted(VIETNAMESE_TO_RUSSIAN),
        ).copy(
            isListening = false,
            activeSpeechDirection = null,
        )

        val next = VoiceBridgeReducer.reduce(
            reverseTurn,
            VoiceBridgeAction.TargetLanguageSelected(AppLanguage.ENGLISH),
        )

        assertEquals(AppLanguage.RUSSIAN, next.configuredSourceLanguage)
        assertEquals(AppLanguage.ENGLISH, next.configuredTargetLanguage)
        assertEquals(AppLanguage.RUSSIAN, next.sourceLanguage)
        assertEquals(AppLanguage.ENGLISH, next.targetLanguage)
    }

    @Test
    fun `settings swap updates configured pair without double swapping reverse turn text`() {
        val reverseTurn = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.VIETNAMESE,
            targetLanguage = AppLanguage.RUSSIAN,
            configuredSourceLanguage = AppLanguage.RUSSIAN,
            configuredTargetLanguage = AppLanguage.VIETNAMESE,
            transcript = "xin chào",
            translation = "привет",
            translatedSourceText = "xin chào",
        )

        val next = VoiceBridgeReducer.reduce(
            reverseTurn,
            VoiceBridgeAction.LanguagesSwapped,
        )

        assertEquals(AppLanguage.VIETNAMESE, next.configuredSourceLanguage)
        assertEquals(AppLanguage.RUSSIAN, next.configuredTargetLanguage)
        assertEquals("xin chào", next.transcript)
        assertEquals("привет", next.translation)
    }

    @Test
    fun `release from another direction cannot stop active touch session`() {
        val listening = VoiceBridgeReducer.reduce(
            VoiceBridgeUiState(),
            VoiceBridgeAction.PressStarted(RUSSIAN_TO_VIETNAMESE),
        )

        val unchanged = VoiceBridgeReducer.reduce(
            listening,
            VoiceBridgeAction.PressReleased(VIETNAMESE_TO_RUSSIAN),
        )

        assertEquals(listening, unchanged)
    }

    @Test
    fun `speech error stops active recognition and is visible`() {
        val listening = VoiceBridgeUiState(isListening = true)

        val failed = VoiceBridgeReducer.reduce(
            listening,
            VoiceBridgeAction.SpeechFailed("микрофон занят"),
        )

        assertFalse(failed.isListening)
        assertFalse(failed.isProcessing)
        assertEquals("микрофон занят", failed.error)
    }

    @Test
    fun `translation cancellation clears busy state`() {
        val translating = VoiceBridgeUiState(isTranslating = true)

        val cancelled = VoiceBridgeReducer.reduce(
            translating,
            VoiceBridgeAction.TranslationCancelled,
        )

        assertFalse(cancelled.isTranslating)
    }

    @Test
    fun `editing transcript invalidates the previous translation`() {
        val completed = VoiceBridgeUiState(
            transcript = "привет",
            translation = "hello",
            backTranslation = "привет",
            backTranslatedSourceText = "hello",
        )

        val edited = VoiceBridgeReducer.reduce(
            completed,
            VoiceBridgeAction.TranscriptEdited("добрый день"),
        )

        assertEquals("добрый день", edited.transcript)
        assertEquals("", edited.translation)
        assertEquals("", edited.backTranslation)
        assertNull(edited.backTranslatedSourceText)
    }

    @Test
    fun `stale russian check cannot replace a newer translation`() {
        val current = VoiceBridgeUiState(
            transcript = "новая фраза",
            translation = "câu mới",
            translatedSourceText = "новая фраза",
        )

        val unchanged = VoiceBridgeReducer.reduce(
            current,
            VoiceBridgeAction.BackTranslationSucceeded(
                sourceText = "câu cũ",
                text = "старая фраза",
            ),
        )

        assertEquals(current, unchanged)
    }

    @Test
    fun `changed final transcript invalidates partial translation before swap`() {
        val partialTranslated = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.ENGLISH,
            transcript = "добрый",
            translation = "good",
            translatedSourceText = "добрый",
            isListening = true,
        )

        val final = VoiceBridgeReducer.reduce(
            partialTranslated,
            VoiceBridgeAction.SpeechFinal("добрый день"),
        )
        val swapped = VoiceBridgeReducer.reduce(
            final,
            VoiceBridgeAction.LanguagesSwapped,
        )

        assertEquals("", final.translation)
        assertEquals("", swapped.transcript)
    }

    @Test
    fun `resolved currency conversions are accepted in any matching language direction`() {
        val state = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.VIETNAMESE,
            transcript = "15000000",
        )
        val conversion = CurrencyConversionUi(
            sourceText = "15000000",
            direction = RUSSIAN_TO_VIETNAMESE,
            amountVnd = 15_000_000L,
            estimatedRubles = 51_016L,
            estimatedUsdCents = 61_524L,
            estimatedEurCents = 52_000L,
            estimatedUahKopecks = 2_700_000L,
            exchangeBufferPercent = 5,
            cbrEffectiveDate = "23.08.2026",
            isStale = false,
        )

        val resolved = VoiceBridgeReducer.reduce(
            state,
            VoiceBridgeAction.CurrencyConversionsUpdated(
                sourceText = conversion.sourceText,
                direction = conversion.direction,
                conversions = listOf(conversion),
            ),
        )
        val stale = VoiceBridgeReducer.reduce(
            state.copy(transcript = "2 triệu"),
            VoiceBridgeAction.CurrencyConversionsUpdated(
                sourceText = conversion.sourceText,
                direction = conversion.direction,
                conversions = listOf(conversion),
            ),
        )

        assertEquals(listOf(conversion), resolved.currencyConversions)
        assertTrue(stale.currencyConversions.isEmpty())
    }

    @Test
    fun `editing or starting another turn clears currency conversion`() {
        val conversion = CurrencyConversionUi(
            sourceText = "1 triệu",
            direction = VIETNAMESE_TO_RUSSIAN,
            amountVnd = 1_000_000L,
            estimatedRubles = 3_401L,
            estimatedUsdCents = 4_102L,
            estimatedEurCents = 3_500L,
            estimatedUahKopecks = 180_000L,
            exchangeBufferPercent = 5,
            cbrEffectiveDate = "23.08.2026",
            isStale = true,
        )
        val converted = VoiceBridgeUiState(
            sourceLanguage = AppLanguage.VIETNAMESE,
            targetLanguage = AppLanguage.RUSSIAN,
            transcript = "1 triệu",
            currencyConversions = listOf(conversion),
        )

        val edited = VoiceBridgeReducer.reduce(
            converted,
            VoiceBridgeAction.TranscriptEdited("2 triệu"),
        )
        val newTurn = VoiceBridgeReducer.reduce(
            converted,
            VoiceBridgeAction.PressStarted(RUSSIAN_TO_VIETNAMESE),
        )

        assertTrue(edited.currencyConversions.isEmpty())
        assertTrue(newTurn.currencyConversions.isEmpty())
    }

    private companion object {
        val RUSSIAN_TO_VIETNAMESE = SpeechDirection(
            sourceLanguage = AppLanguage.RUSSIAN,
            targetLanguage = AppLanguage.VIETNAMESE,
        )
        val VIETNAMESE_TO_RUSSIAN = SpeechDirection(
            sourceLanguage = AppLanguage.VIETNAMESE,
            targetLanguage = AppLanguage.RUSSIAN,
        )
    }
}
