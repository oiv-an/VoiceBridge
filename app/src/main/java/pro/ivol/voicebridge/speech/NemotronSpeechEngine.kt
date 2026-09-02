package pro.ivol.voicebridge.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import pro.ivol.voicebridge.domain.SpeechEngine
import pro.ivol.voicebridge.domain.SpeechEvent
import pro.ivol.voicebridge.domain.PreparableSpeechEngine

/**
 * True streaming, fully local speech recognition backed by NVIDIA Nemotron 3.5
 * exported to ONNX and executed by sherpa-onnx.
 */
class NemotronSpeechEngine(
    context: Context,
    private val modelDirectory: File = NemotronModelSpec.directory(context),
    private val numThreads: Int = 2,
) : SpeechEngine, PreparableSpeechEngine {
    override val displayName = "NVIDIA Nemotron 3.5 (local)"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voicebridge-nemotron")
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
    private var recognizer: OnlineRecognizer? = null

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
                        Log.e(TAG, "Failed to prepare Nemotron", throwable)
                        if (!destroyed.get()) {
                            dispatch(
                                SpeechEvent.Error(
                                    "Could not load the local NVIDIA model: " +
                                        (throwable.message ?: "unknown error"),
                                ),
                            )
                        }
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            preparationStarted.set(false)
            dispatch(SpeechEvent.Error("The local speech recognizer has already stopped."))
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
                languageTag = languageTag.ifBlank { "auto" },
            ).also(activeSession::set)
        }

        try {
            worker.execute {
                try {
                    runSession(session)
                } catch (throwable: Throwable) {
                    Log.e(
                        TAG,
                        "Nemotron session=${session.id} failed language=${session.languageTag}",
                        throwable,
                    )
                    if (!destroyed.get() && !session.discarded.get()) {
                        finishSession(
                            session,
                            SpeechEvent.Error(
                                throwable.message
                                    ?: "Could not start local speech recognition.",
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
                SpeechEvent.Error("The local speech recognizer has already stopped."),
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
        armFinalizationWatchdog(session)
        requestRecorderStop()
    }

    override fun cancelListening() {
        val (cancellationGeneration, session) = synchronized(sessionStartLock) {
            lastSessionId.incrementAndGet() to activeSession.getAndSet(null)
        }
        session?.let {
            it.discarded.set(true)
            it.captureRequested.set(false)
            cancelFinalizationWatchdog(it)
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
            cancelFinalizationWatchdog(session)
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

        val languageHint = NemotronModelSpec.languageHint(session.languageTag)
        var stream = createConfiguredStream(activeRecognizer, languageHint)
        try {
            Log.i(
                TAG,
                "Starting Nemotron session=${session.id} language=$languageHint",
            )
            val recorder = createAudioRecord()
            synchronized(audioLock) { currentRecorder = recorder }

            val transcript = NemotronHoldTranscriptAccumulator()
            var segmentId = 0L
            var latestSegmentText = ""
            var lastPublishedText = ""
            var capturedSamples = 0L
            var peakAmplitude = 0
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
                            capturedSamples += count
                            stream.acceptWaveform(samples, SAMPLE_RATE)

                            val segmentText = decodeAvailable(activeRecognizer, stream)
                            if (segmentText.isNotBlank()) latestSegmentText = segmentText

                            val preview = transcript.preview(segmentId, segmentText)
                            if (preview.isNotBlank() && preview != lastPublishedText) {
                                lastPublishedText = preview
                                dispatchForSession(
                                    session,
                                    SpeechEvent.PartialResult(preview),
                                )
                            }

                            if (
                                session.captureRequested.get() &&
                                activeRecognizer.isEndpoint(stream)
                            ) {
                                val endpointText = segmentText.ifBlank { latestSegmentText }
                                val committed = transcript.commit(segmentId, endpointText)
                                val committedText = transcript.committedText
                                if (
                                    committedText.isNotBlank() &&
                                    committedText != lastPublishedText
                                ) {
                                    lastPublishedText = committedText
                                    dispatchForSession(
                                        session,
                                        SpeechEvent.PartialResult(committedText),
                                    )
                                }

                                val completedStream = stream
                                stream = createConfiguredStream(
                                    activeRecognizer,
                                    languageHint,
                                )
                                completedStream.release()
                                if (committed) {
                                    Log.i(
                                        TAG,
                                        "Nemotron session=${session.id} committed " +
                                            "segment=$segmentId " +
                                            "totalChars=${committedText.length}",
                                    )
                                }
                                segmentId += 1L
                                latestSegmentText = ""
                            }
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

            stream.acceptWaveform(FloatArray(RIGHT_PADDING_SAMPLES), SAMPLE_RATE)
            stream.inputFinished()
            val finalSegmentText = decodeAvailable(activeRecognizer, stream)
                .ifBlank { latestSegmentText }
            transcript.commit(segmentId, finalSegmentText)
            val finalText = transcript.committedText
            val capturedMilliseconds = capturedSamples * 1_000L / SAMPLE_RATE
            Log.i(
                TAG,
                "Finished Nemotron session=${session.id} language=$languageHint " +
                    "audioMs=$capturedMilliseconds peak=$peakAmplitude " +
                    "segments=${segmentId + 1L} textChars=${finalText.length}",
            )

            if (finalText.isBlank()) {
                val message = when {
                    capturedMilliseconds < MINIMUM_SPEECH_DURATION_MS ->
                        "Hold the button while speaking, then release it."
                    peakAmplitude < MINIMUM_AUDIBLE_PEAK ->
                        "No audible speech reached the microphone."
                    else -> "Speech was not recognized."
                }
                finishSession(session, SpeechEvent.Error(message))
            } else {
                finishSession(session, SpeechEvent.FinalResult(finalText))
            }
        } finally {
            stream.release()
        }
    }

    private fun createConfiguredStream(
        recognizer: OnlineRecognizer,
        languageHint: String,
    ): OnlineStream {
        val stream = recognizer.createStream()
        try {
            check(stream.ptr != 0L) { "Could not create a recognition stream." }
            stream.setOption("language", languageHint)
            check(stream.getOption("language") == languageHint) {
                "Nemotron did not accept the $languageHint language hint."
            }
            // Every endpoint starts a genuinely fresh decoder stream. Giving
            // each one the same left context as the initial stream prevents
            // the first phoneme after a pause from being clipped.
            stream.acceptWaveform(FloatArray(LEFT_PADDING_SAMPLES), SAMPLE_RATE)
            decodeAvailable(recognizer, stream)
            return stream
        } catch (throwable: Throwable) {
            stream.release()
            throw throwable
        }
    }

    private fun decodeAvailable(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
    ): String {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    private fun getOrCreateRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }

        check(!destroyed.get()) { "The local speech recognizer has stopped." }

        require(NemotronModelSpec.isVerifiedDirectory(modelDirectory)) {
            "The Nemotron model failed its integrity check."
        }

        val encoder = requiredModelFile("encoder.int8.onnx")
        val decoder = requiredModelFile("decoder.int8.onnx")
        val joiner = requiredModelFile("joiner.int8.onnx")
        val tokens = requiredModelFile("tokens.txt")

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
                dither = 0.0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, EMPTY_TRAILING_SILENCE_SECONDS, 0.0f),
                rule2 = EndpointRule(true, SPEECH_TRAILING_SILENCE_SECONDS, 0.0f),
                rule3 = EndpointRule(false, 0.0f, MAX_SEGMENT_SECONDS),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )

        val created = OnlineRecognizer(assetManager = null, config = config)
        if (destroyed.get()) {
            created.release()
            error("The local speech recognizer stopped while loading.")
        }
        recognizer = created
        return created
    }

    private fun requiredModelFile(name: String): File =
        File(modelDirectory, name).also { file ->
            require(file.isFile && file.length() > 0L) {
                "The Nemotron model is not installed: $name"
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
                activeSession.get() === session
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
        cancelFinalizationWatchdog(session)
        if (activeSession.compareAndSet(session, null)) {
            dispatchIfLatest(session, event)
        }
    }

    private fun armFinalizationWatchdog(session: Session) {
        val timeout = Runnable {
            if (
                !destroyed.get() &&
                activeSession.compareAndSet(session, null)
            ) {
                session.discarded.set(true)
                session.captureRequested.set(false)
                session.finalizationWatchdog.set(null)
                if (lastSessionId.get() == session.id) {
                    eventListener?.invoke(
                        SpeechEvent.Unresponsive(
                            "Local speech recognition did not respond in time. " +
                                "Switch to Google and try again.",
                        ),
                    )
                }
            }
        }
        session.finalizationWatchdog.getAndSet(timeout)?.let(mainHandler::removeCallbacks)
        mainHandler.postDelayed(timeout, FINALIZATION_TIMEOUT_MS)
    }

    private fun cancelFinalizationWatchdog(session: Session) {
        session.finalizationWatchdog.getAndSet(null)?.let(mainHandler::removeCallbacks)
    }

    private fun dispatchForSession(session: Session, event: SpeechEvent) {
        mainHandler.post {
            if (
                !destroyed.get() &&
                activeSession.get() === session &&
                lastSessionId.get() == session.id
            ) {
                eventListener?.invoke(event)
            }
        }
    }

    private fun dispatchIfLatest(session: Session, event: SpeechEvent) {
        dispatchForGeneration(session.id, event)
    }

    private fun dispatchForGeneration(generation: Long, event: SpeechEvent) {
        mainHandler.post {
            if (!destroyed.get() && lastSessionId.get() == generation) {
                eventListener?.invoke(event)
            }
        }
    }

    private fun dispatch(event: SpeechEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!destroyed.get()) eventListener?.invoke(event)
        } else {
            mainHandler.post {
                if (!destroyed.get()) eventListener?.invoke(event)
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val READ_SAMPLES = 1_600
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val AUDIO_BUFFER_SECONDS = 4
        const val LEFT_PADDING_SAMPLES = SAMPLE_RATE / 2
        const val RIGHT_PADDING_SAMPLES = SAMPLE_RATE * 4 / 5
        const val FINALIZATION_TIMEOUT_MS = 20_000L
        const val MINIMUM_SPEECH_DURATION_MS = 500L
        const val MINIMUM_AUDIBLE_PEAK = 256
        const val EMPTY_TRAILING_SILENCE_SECONDS = 2.4f
        const val SPEECH_TRAILING_SILENCE_SECONDS = 1.4f
        const val MAX_SEGMENT_SECONDS = 20.0f
        const val TAG = "VoiceBridgeNemotron"
    }

    private data class Session(
        val id: Long,
        val languageTag: String,
        val captureRequested: AtomicBoolean = AtomicBoolean(true),
        val discarded: AtomicBoolean = AtomicBoolean(false),
        val finalizationWatchdog: AtomicReference<Runnable?> = AtomicReference(null),
    )
}
