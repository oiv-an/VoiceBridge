package pro.ivol.voicebridge.speech

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller
import pro.ivol.voicebridge.translation.MlKitTranslationModelInstaller

/** Installs the complete speech-and-translation bundle as one UI operation. */
class CombinedLocalModelInstaller private constructor(
    private val components: List<Component>,
) : LocalModelInstaller {
    constructor(context: Context) : this(
        components = listOf(
            Component(RUSSIAN_SPEECH_NAME, NemotronModelInstaller(context)),
            Component(VIETNAMESE_SPEECH_NAME, VietnameseModelInstaller(context)),
            Component(
                RUSSIAN_TRANSLATION_NAME,
                MlKitTranslationModelInstaller(context, AppLanguage.RUSSIAN),
                alwaysValidate = true,
            ),
            Component(
                VIETNAMESE_TRANSLATION_NAME,
                MlKitTranslationModelInstaller(context, AppLanguage.VIETNAMESE),
                alwaysValidate = true,
            ),
        ),
    )

    internal constructor(
        nemotronInstaller: LocalModelInstaller,
        vietnameseInstaller: LocalModelInstaller,
        russianTranslationInstaller: LocalModelInstaller,
        vietnameseTranslationInstaller: LocalModelInstaller,
    ) : this(
        components = listOf(
            Component(RUSSIAN_SPEECH_NAME, nemotronInstaller),
            Component(VIETNAMESE_SPEECH_NAME, vietnameseInstaller),
            Component(
                RUSSIAN_TRANSLATION_NAME,
                russianTranslationInstaller,
                alwaysValidate = true,
            ),
            Component(
                VIETNAMESE_TRANSLATION_NAME,
                vietnameseTranslationInstaller,
                alwaysValidate = true,
            ),
        ),
    )

    private val installMutex = Mutex()
    private val cancellationEpoch = AtomicLong(0L)
    private val closed = AtomicBoolean(false)

    override val isInstalled: Boolean
        get() = components.all { it.installer.isInstalled }

    override fun install(): Flow<LocalModelInstallEvent> = flow {
        installMutex.withLock {
            val operationEpoch = cancellationEpoch.get()
            try {
                checkActive(operationEpoch)
                var lastOverallProgress = 0f
                var allComponentsWereAlreadyInstalled = true

                components.forEachIndexed { componentOffset, component ->
                    checkActive(operationEpoch)
                    val componentIndex = componentOffset + 1

                    suspend fun emitProgress(localProgress: Float, detail: String) {
                        val aggregateProgress = aggregateProgress(
                            componentOffset = componentOffset,
                            componentCount = components.size,
                            localProgress = localProgress,
                        ).coerceAtLeast(lastOverallProgress)
                        lastOverallProgress = aggregateProgress
                        emit(
                            LocalModelInstallEvent.BundleProgress(
                                componentName = component.name,
                                componentIndex = componentIndex,
                                componentCount = components.size,
                                progress = aggregateProgress,
                                detail = detail,
                            ),
                        )
                    }

                    if (component.installer.isInstalled && !component.alwaysValidate) {
                        emitProgress(localProgress = 1f, detail = "Ready")
                        return@forEachIndexed
                    }

                    if (!component.installer.isInstalled) {
                        allComponentsWereAlreadyInstalled = false
                    }
                    emitProgress(localProgress = 0f, detail = "Preparing...")
                    var childInstalled = false

                    try {
                        component.installer.install().collect { event ->
                            checkActive(operationEpoch)
                            when (event) {
                                is LocalModelInstallEvent.BundleProgress -> emitProgress(
                                    localProgress = event.progress,
                                    detail = event.detail,
                                )

                                is LocalModelInstallEvent.CheckingStorage -> emitProgress(
                                    localProgress = CHECKING_PROGRESS,
                                    detail = "Checking free space...",
                                )

                                is LocalModelInstallEvent.Downloading -> {
                                    val downloadProgress = event.downloadFraction()
                                    emitProgress(
                                        localProgress = DOWNLOAD_START_PROGRESS +
                                            downloadProgress * DOWNLOAD_PROGRESS_SPAN,
                                        detail = if (event.hasKnownSize()) {
                                            "Downloading ${(downloadProgress * 100).toInt()}%"
                                        } else {
                                            "Downloading..."
                                        },
                                    )
                                }

                                is LocalModelInstallEvent.Verifying -> emitProgress(
                                    localProgress = VERIFYING_PROGRESS,
                                    detail = "Verifying...",
                                )

                                LocalModelInstallEvent.Activating -> emitProgress(
                                    localProgress = ACTIVATING_PROGRESS,
                                    detail = "Installing...",
                                )

                                is LocalModelInstallEvent.Installed -> {
                                    // A child terminal event only completes this component.
                                    childInstalled = true
                                    if (!event.alreadyInstalled) {
                                        allComponentsWereAlreadyInstalled = false
                                    }
                                }

                                is LocalModelInstallEvent.Failed -> throw ComponentInstallFailure(event)
                            }
                        }
                    } catch (failure: ComponentInstallFailure) {
                        emit(failure.event)
                        return@withLock
                    }

                    checkActive(operationEpoch)
                    if (!childInstalled || !component.installer.isInstalled) {
                        emit(
                            LocalModelInstallEvent.Failed(
                                "${component.name} installation did not complete.",
                            ),
                        )
                        return@withLock
                    }
                    emitProgress(localProgress = 1f, detail = "Ready")
                }

                checkActive(operationEpoch)
                check(isInstalled) {
                    "The offline bundle failed its final installation check."
                }
                // Child Installed events are deliberately suppressed. This is the
                // only terminal success event exposed by the complete bundle.
                emit(
                    LocalModelInstallEvent.Installed(
                        alreadyInstalled = allComponentsWereAlreadyInstalled,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                emit(
                    LocalModelInstallEvent.Failed(
                        message = throwable.message
                            ?: "Could not install the complete offline bundle.",
                        cause = throwable,
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        cancellationEpoch.incrementAndGet()
        propagateToEveryComponent { it.cancel() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancellationEpoch.incrementAndGet()
        propagateToEveryComponent { it.close() }
    }

    private suspend fun checkActive(operationEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (closed.get() || cancellationEpoch.get() != operationEpoch) {
            throw CancellationException("Local model installation was cancelled.")
        }
    }

    private fun propagateToEveryComponent(action: (LocalModelInstaller) -> Unit) {
        var firstFailure: Throwable? = null
        components.forEach { component ->
            try {
                action(component.installer)
            } catch (throwable: Throwable) {
                if (firstFailure == null) {
                    firstFailure = throwable
                } else {
                    firstFailure.addSuppressed(throwable)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    private data class Component(
        val name: String,
        val installer: LocalModelInstaller,
        val alwaysValidate: Boolean = false,
    )

    private class ComponentInstallFailure(
        val event: LocalModelInstallEvent.Failed,
    ) : RuntimeException(event.message, event.cause)

    private companion object {
        const val RUSSIAN_SPEECH_NAME = "Russian speech · NVIDIA"
        const val VIETNAMESE_SPEECH_NAME = "Vietnamese speech"
        const val RUSSIAN_TRANSLATION_NAME = "Russian offline translation"
        const val VIETNAMESE_TRANSLATION_NAME = "Vietnamese offline translation"

        const val CHECKING_PROGRESS = 0.03f
        const val DOWNLOAD_START_PROGRESS = 0.05f
        const val DOWNLOAD_PROGRESS_SPAN = 0.85f
        const val VERIFYING_PROGRESS = 0.95f
        const val ACTIVATING_PROGRESS = 0.98f

        fun aggregateProgress(
            componentOffset: Int,
            componentCount: Int,
            localProgress: Float,
        ): Float = (componentOffset + localProgress.coerceIn(0f, 1f)) / componentCount

        fun LocalModelInstallEvent.Downloading.hasKnownSize(): Boolean =
            totalBytes > 0L || fileSizeBytes > 0L

        fun LocalModelInstallEvent.Downloading.downloadFraction(): Float = when {
            totalBytes > 0L -> downloadedBytes.toDouble().div(totalBytes).toFloat()
            fileSizeBytes > 0L -> fileDownloadedBytes.toDouble().div(fileSizeBytes).toFloat()
            else -> 0f
        }.coerceIn(0f, 1f)
    }
}
