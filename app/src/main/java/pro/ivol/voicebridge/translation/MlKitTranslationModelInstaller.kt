package pro.ivol.voicebridge.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller

/**
 * Installs one ML Kit translation model managed by Google Play services.
 *
 * English is intentionally excluded: ML Kit uses it as the translation hub and
 * does not expose a separately downloadable English translation model. A model
 * download is owned by [RemoteModelManager], so cancelling this installer stops
 * our operation but deliberately leaves the resumable Google task and its data
 * alone.
 */
class MlKitTranslationModelInstaller internal constructor(
    private val language: AppLanguage,
    private val modelStore: MlKitTranslationModelStore,
    private val installationMarker: MlKitTranslationModelInstallationMarker,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalModelInstaller {
    constructor(context: Context, language: AppLanguage) : this(
        language = language,
        modelStore = RemoteModelManagerTranslationModelStore(language),
        installationMarker = SharedPreferencesTranslationModelInstallationMarker(
            context = context.applicationContext,
            language = language,
        ),
    )

    private val installMutex = Mutex()
    private val cancellationEpoch = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val modelFileName = "ml-kit-translation-${language.mlKitLanguageCode()}"

    init {
        require(language == AppLanguage.RUSSIAN || language == AppLanguage.VIETNAMESE) {
            "Only the Russian and Vietnamese ML Kit translation models can be downloaded."
        }
    }

    override val isInstalled: Boolean
        get() = installationMarker.isInstalled

    override fun install(): Flow<LocalModelInstallEvent> = flow {
        installMutex.withLock {
            val operationEpoch = cancellationEpoch.get()
            try {
                checkActive(operationEpoch)
                emit(
                    LocalModelInstallEvent.CheckingStorage(
                        requiredFreeBytes = 0L,
                        availableFreeBytes = 0L,
                    ),
                )

                // The marker is only a synchronous UI hint. Reconcile it with ML
                // Kit on every explicit install so external model eviction cannot
                // leave the app claiming that an absent model is available.
                installationMarker.isInstalled = false
                checkActive(operationEpoch)
                val wasAlreadyDownloaded = modelStore.isModelDownloaded()
                checkActive(operationEpoch)

                if (wasAlreadyDownloaded) {
                    emit(verifyingEvent())
                    checkActive(operationEpoch)
                    installationMarker.isInstalled = true
                    checkActive(operationEpoch)
                    emit(LocalModelInstallEvent.Installed(alreadyInstalled = true))
                    return@withLock
                }

                emit(
                    LocalModelInstallEvent.Downloading(
                        fileName = modelFileName,
                        fileIndex = 1,
                        fileCount = 1,
                        fileDownloadedBytes = 0L,
                        fileSizeBytes = 0L,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                    ),
                )
                checkActive(operationEpoch)
                modelStore.downloadModel()
                checkActive(operationEpoch)

                emit(verifyingEvent())
                val downloaded = modelStore.isModelDownloaded()
                checkActive(operationEpoch)
                check(downloaded) {
                    "ML Kit finished without making the ${language.displayName} model available."
                }

                installationMarker.isInstalled = true
                checkActive(operationEpoch)
                emit(LocalModelInstallEvent.Installed(alreadyInstalled = false))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                installationMarker.isInstalled = false
                emit(
                    LocalModelInstallEvent.Failed(
                        message = throwable.message
                            ?: "Could not install the ${language.displayName} ML Kit translation model.",
                        cause = throwable,
                    ),
                )
            }
        }
    }.flowOn(workDispatcher)

    override fun cancel() {
        // RemoteModelManager tasks cannot be cancelled. Advancing the epoch makes
        // their eventual callback stale, while preserving any resumable download.
        cancellationEpoch.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancellationEpoch.incrementAndGet()
    }

    private fun verifyingEvent() = LocalModelInstallEvent.Verifying(
        fileName = modelFileName,
        fileIndex = 1,
        fileCount = 1,
    )

    private suspend fun checkActive(operationEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (closed.get() || cancellationEpoch.get() != operationEpoch) {
            throw CancellationException("ML Kit translation model installation was cancelled.")
        }
    }
}

/** Boundary kept small so installer behavior has pure JVM tests. */
internal interface MlKitTranslationModelStore {
    suspend fun isModelDownloaded(): Boolean

    suspend fun downloadModel()
}

/** Synchronous marker used by [LocalModelInstaller.isInstalled]. */
internal interface MlKitTranslationModelInstallationMarker {
    var isInstalled: Boolean
}

private class RemoteModelManagerTranslationModelStore(
    language: AppLanguage,
    private val remoteModelManager: RemoteModelManager = RemoteModelManager.getInstance(),
) : MlKitTranslationModelStore {
    private val remoteModel = TranslateRemoteModel.Builder(language.mlKitLanguageCode()).build()
    private val emptyDownloadConditions = DownloadConditions.Builder().build()
    private val taskLock = Any()
    private var activeDownloadTask: Task<Void>? = null

    override suspend fun isModelDownloaded(): Boolean =
        remoteModelManager.isModelDownloaded(remoteModel).awaitMlKitTask()

    override suspend fun downloadModel() {
        val task = synchronized(taskLock) {
            activeDownloadTask ?: remoteModelManager
                .download(remoteModel, emptyDownloadConditions)
                .also { createdTask ->
                    activeDownloadTask = createdTask
                    createdTask.addOnCompleteListener { completedTask ->
                        if (completedTask.isCanceled || !completedTask.isSuccessful) {
                            synchronized(taskLock) {
                                if (activeDownloadTask === createdTask) {
                                    activeDownloadTask = null
                                }
                            }
                        }
                    }
                }
        }

        try {
            task.awaitMlKitTask()
            clearTask(task)
        } catch (cancellation: CancellationException) {
            // Keep a manager-owned running task for the next collection.
            if (task.isComplete) clearTask(task)
            throw cancellation
        } catch (throwable: Throwable) {
            clearTask(task)
            throw throwable
        }
    }

    private fun clearTask(task: Task<Void>) {
        synchronized(taskLock) {
            if (activeDownloadTask === task) activeDownloadTask = null
        }
    }
}

private class SharedPreferencesTranslationModelInstallationMarker(
    context: Context,
    language: AppLanguage,
) : MlKitTranslationModelInstallationMarker {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val key = "installed_${language.mlKitLanguageCode()}"

    override var isInstalled: Boolean
        get() = preferences.getBoolean(key, false)
        set(value) {
            preferences.edit { putBoolean(key, value) }
        }

    private companion object {
        const val PREFERENCES_NAME = "ml_kit_translation_models"
    }
}

private fun AppLanguage.mlKitLanguageCode(): String = when (this) {
    AppLanguage.RUSSIAN -> TranslateLanguage.RUSSIAN
    AppLanguage.VIETNAMESE -> TranslateLanguage.VIETNAMESE
    AppLanguage.ENGLISH -> throw IllegalArgumentException(
        "The English ML Kit translation model must not be downloaded.",
    )
}

private suspend fun <T> Task<T>.awaitMlKitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) continuation.resumeWithException(throwable)
        }
        addOnCanceledListener { continuation.cancel() }
    }
