package pro.ivol.voicebridge.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import pro.ivol.voicebridge.domain.PreparableSpeechEngine
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent

/**
 * Push-to-talk Vietnamese recognition using the offline 30M Zipformer model.
 * Audio is captured while the button is held and decoded only after release.
 */
class VietnameseSpeechEngine(
    context: Context,
    private val modelDirectory: File = VietnameseModelSpec.directory(context),
    private val numThreads: Int = 2,
) : SpeechEngine, PreparableSpeechEngine {
    override val displayName = "Vietnamese Zipformer (local)"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voicebridge-vietnamese")
    }

    private val destroyed = AtomicBoolean(false)
    private val preparationStarted = AtomicBoolean(false)
    private val prepared = AtomicBoolean(false)
    private val lastSessionId = AtomicLong(0L)
    private val activeSession = AtomicReference<Session?>(null)
    private val sessionStartLock = Any()
    private val audioLock = Any()

    @Volatile
    private var eventListener: ((SpeechEvent) -> Unit)? = null

    private var currentRecorder: AudioRecord? = null

    // Constructed, used, and released only on [worker].
    private var recognizer: OfflineRecognizer? = null

    override val isPrepared: Boolean
        get() = prepared.get()

    override fun setListener(listener: (SpeechEvent) -> Unit) {
        if (!destroyed.get()) eventListener = listener
    }

    override fun prepare() {
        if (destroyed.get()) return
        if (prepared.get()) {
            dispatch(SpeechEvent.Ready)
            return
        }

        dispatch(SpeechEvent.Preparing)
        if (!preparationStarted.compareAndSet(false, true)) return

        try {
            worker.execute {
                runCatching(::getOrCreateRecognizer).fold(
                    onSuccess = {
                        if (!destroyed.get()) {
                            prepared.set(true)
                            dispatch(SpeechEvent.Ready)
                        }
                    },
                    onFailure = { throwable ->
                        preparationStarted.set(false)
                        Log.e(TAG, "Failed to prepare Vietnamese Zipformer", throwable)
                        if (!destroyed.get()) {
                            dispatch(
                                SpeechEvent.Error(
                                    "Could not load the local Vietnamese model: " +
                                        (throwable.message ?: "unknown error"),
                                ),
                            )
                        }
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            preparationStarted.set(false)
            dispatch(SpeechEvent.Error("The Vietnamese speech recognizer has stopped."))
        }
    }

    override fun startListening(languageTag: String) {
        if (!prepared.get()) {
            prepare()
            return
        }

        val session = synchronized(sessionStartLock) {
            if (destroyed.get() || activeSession.get() != null) return
            Session(
                id = lastSessionId.incrementAndGet(),
                requestedLanguageTag = languageTag,
            ).also(activeSession::set)
        }

        try {
            worker.execute {
                try {
                    runSession(session)
                } catch (throwable: Throwable) {
                    Log.e(
                        TAG,
                        "Vietnamese session=${session.id} failed",
                        throwable,
                    )
                    if (!destroyed.get() && !session.discarded.get()) {
                        finishSession(
                            session,
                            SpeechEvent.Error(
                                throwable.message
                                    ?: "Could not recognize Vietnamese speech.",
                            ),
                        )
                    }
                } finally {
                    session.captureRequested.set(false)
                    activeSession.compareAndSet(session, null)
                }
            }
        } catch (_: RejectedExecutionException) {
            session.captureRequested.set(false)
            finishSession(
                session,
                SpeechEvent.Error("The Vietnamese speech recognizer has stopped."),
            )
        }
    }

    override fun stopListening() {
        val session = synchronized(sessionStartLock) { activeSession.get() }
        if (session == null) {
            dispatch(SpeechEvent.Stopped)
            return
        }

        if (!session.captureRequested.compareAndSet(true, false)) return
        dispatchForSession(session, SpeechEvent.Processing)
        requestRecorderStop()
    }

    override fun cancelListening() {
        val (cancellationGeneration, session) = synchronized(sessionStartLock) {
            lastSessionId.incrementAndGet() to activeSession.getAndSet(null)
        }
        session?.let {
            it.discarded.set(true)
            it.captureRequested.set(false)
            requestRecorderStop()
        }
        dispatchForGeneration(cancellationGeneration, SpeechEvent.Stopped)
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return

        eventListener = null
        prepared.set(false)
        synchronized(sessionStartLock) {
            activeSession.getAndSet(null)
        }?.let { session ->
            session.discarded.set(true)
            session.captureRequested.set(false)
        }
        requestRecorderStop()

        try {
            worker.execute {
                recognizer?.release()
                recognizer = null
            }
        } catch (_: RejectedExecutionException) {
            // The worker is already closed.
        }
        worker.shutdown()
    }

    private fun runSession(session: Session) {
        if (!session.captureRequested.get() || destroyed.get()) {
            finishStoppedUnlessDiscarded(session)
            return
        }

        val activeRecognizer = getOrCreateRecognizer()
        if (!session.captureRequested.get() || destroyed.get()) {
            finishStoppedUnlessDiscarded(session)
            return
        }

        val recorder = createAudioRecord()
        val chunks = ArrayList<FloatArray>()
        var capturedSamples = 0L
        var peakAmplitude = 0
        synchronized(audioLock) { currentRecorder = recorder }

        try {
            if (!session.captureRequested.get() || destroyed.get()) {
                finishStoppedUnlessDiscarded(session)
                return
            }

            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "The microphone did not enter recording mode."
            }
            if (!session.captureRequested.get() || destroyed.get()) {
                finishStoppedUnlessDiscarded(session)
                return
            }

            Log.i(
                TAG,
                "Starting Vietnamese session=${session.id} " +
                    "requestedLanguage=${session.requestedLanguageTag}",
            )
            dispatchListening(session)

            val pcm16 = ShortArray(READ_SAMPLES)
            while (session.captureRequested.get() && !destroyed.get()) {
                val count = recorder.read(
                    pcm16,
                    0,
                    pcm16.size,
                    AudioRecord.READ_BLOCKING,
                )

                when {
                    count > 0 -> {
                        val samples = FloatArray(count)
                        for (index in 0 until count) {
                            val pcmSample = pcm16[index].toInt()
                            samples[index] = pcmSample / 32768.0f
                            peakAmplitude = maxOf(
                                peakAmplitude,
                                kotlin.math.abs(pcmSample),
                            )
                        }
                        chunks += samples
                        capturedSamples += count
                    }

                    count < 0 && session.captureRequested.get() ->
                        error("Microphone read error: $count")
                }
            }
        } finally {
            releaseRecorder(recorder)
        }

        if (
            destroyed.get() ||
            session.discarded.get() ||
            activeSession.get() !== session
        ) {
            return
        }

        val capturedMilliseconds = capturedSamples * 1_000L / SAMPLE_RATE
        if (capturedMilliseconds < MINIMUM_SPEECH_DURATION_MS) {
            finishSession(
                session,
                SpeechEvent.Error("Hold the button while speaking, then release it."),
            )
            return
        }
        if (peakAmplitude < MINIMUM_AUDIBLE_PEAK) {
            finishSession(
                session,
                SpeechEvent.Error("No audible speech reached the microphone."),
            )
            return
        }

        val stream = activeRecognizer.createStream()
        val rawText = try {
            chunks.forEach { samples ->
                stream.acceptWaveform(samples, SAMPLE_RATE)
            }
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }

        if (
            destroyed.get() ||
            session.discarded.get() ||
            activeSession.get() !== session
        ) {
            return
        }

        val finalText = normalizeAllCaps(rawText)
        Log.i(
            TAG,
            "Finished Vietnamese session=${session.id} " +
                "audioMs=$capturedMilliseconds peak=$peakAmplitude " +
                "textChars=${finalText.length}",
        )
        if (finalText.isBlank()) {
            finishSession(session, SpeechEvent.Error("Speech was not recognized."))
        } else {
            finishSession(session, SpeechEvent.FinalResult(finalText))
        }
    }

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        check(!destroyed.get()) { "The Vietnamese speech recognizer has stopped." }
        require(VietnameseModelSpec.isVerifiedDirectory(modelDirectory)) {
            "The Vietnamese model failed its integrity check."
        }

        val encoder = requiredModelFile("encoder.int8.onnx")
        val decoder = requiredModelFile("decoder.onnx")
        val joiner = requiredModelFile("joiner.int8.onnx")
        val tokens = requiredModelFile("tokens.txt")

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
                dither = 0.0f,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "transducer",
                tokens = tokens.absolutePath,
            ),
            decodingMethod = "greedy_search",
        )

        val created = OfflineRecognizer(assetManager = null, config = config)
        if (destroyed.get()) {
            created.release()
            error("The Vietnamese speech recognizer stopped while loading.")
        }
        recognizer = created
        return created
    }

    private fun requiredModelFile(name: String): File =
        File(modelDirectory, name).also { file ->
            require(file.isFile && file.length() > 0L) {
                "The Vietnamese model is not installed: $name"
            }
        }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferBytes > 0) {
            "This device does not support mono PCM16 audio at 16 kHz."
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val bufferSizeBytes = maxOf(
            minimumBufferBytes,
            SAMPLE_RATE * PCM16_BYTES_PER_SAMPLE * AUDIO_BUFFER_SECONDS,
        )

        for (
            audioSource in intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
        ) {
            val recorder = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build()
            }.onFailure { throwable ->
                Log.w(TAG, "Audio source $audioSource could not be created", throwable)
            }.getOrNull() ?: continue

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Using Android audio source=$audioSource")
                return recorder
            }
            recorder.release()
        }
        error("Could not initialize the microphone.")
    }

    private fun requestRecorderStop() {
        synchronized(audioLock) { currentRecorder?.let(::safeStop) }
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        synchronized(audioLock) {
            if (currentRecorder === recorder) currentRecorder = null
            safeStop(recorder)
            recorder.release()
        }
    }

    private fun safeStop(recorder: AudioRecord) {
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
    }

    private fun dispatchListening(session: Session) {
        mainHandler.post {
            if (
                !destroyed.get() &&
                session.captureRequested.get() &&
                activeSession.get() === session &&
                lastSessionId.get() == session.id
            ) {
                eventListener?.invoke(SpeechEvent.Listening)
            }
        }
    }

    private fun finishStoppedUnlessDiscarded(session: Session) {
        if (!destroyed.get() && !session.discarded.get()) {
            finishSession(session, SpeechEvent.Stopped)
        }
    }

    private fun finishSession(session: Session, event: SpeechEvent) {
        session.captureRequested.set(false)
        if (activeSession.compareAndSet(session, null)) {
            dispatchForGeneration(session.id, event)
        }
    }

    private fun dispatchForSession(session: Session, event: SpeechEvent) {
        dispatchOnMain {
            if (
                !destroyed.get() &&
                activeSession.get() === session &&
                lastSessionId.get() == session.id
            ) {
                eventListener?.invoke(event)
            }
        }
    }

    private fun dispatchForGeneration(generation: Long, event: SpeechEvent) {
        dispatchOnMain {
            if (!destroyed.get() && lastSessionId.get() == generation) {
                eventListener?.invoke(event)
            }
        }
    }

    private fun dispatch(event: SpeechEvent) {
        dispatchOnMain {
            if (!destroyed.get()) eventListener?.invoke(event)
        }
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun normalizeAllCaps(text: String): String {
        val trimmed = text.trim()
        val letters = trimmed.filter(Char::isLetter)
        if (letters.isEmpty() || letters.any { !it.isUpperCase() }) return trimmed

        val lowercase = trimmed.lowercase(VIETNAMESE_LOCALE)
        val firstLetter = lowercase.indexOfFirst(Char::isLetter)
        if (firstLetter < 0) return lowercase

        return buildString(lowercase.length) {
            append(lowercase, 0, firstLetter)
            append(lowercase[firstLetter].toString().uppercase(VIETNAMESE_LOCALE))
            append(lowercase, firstLetter + 1, lowercase.length)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val READ_SAMPLES = 1_600
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val AUDIO_BUFFER_SECONDS = 4
        const val MINIMUM_SPEECH_DURATION_MS = 500L
        const val MINIMUM_AUDIBLE_PEAK = 256
        const val TAG = "VoiceBridgeVietnamese"

        val VIETNAMESE_LOCALE: Locale = Locale.forLanguageTag("vi-VN")
    }

    private data class Session(
        val id: Long,
        val requestedLanguageTag: String,
        val captureRequested: AtomicBoolean = AtomicBoolean(true),
        val discarded: AtomicBoolean = AtomicBoolean(false),
    )
}
