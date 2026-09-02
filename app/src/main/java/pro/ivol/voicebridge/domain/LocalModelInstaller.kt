package pro.ivol.voicebridge.domain

import kotlinx.coroutines.flow.Flow

/**
 * Installs model weights that are deliberately kept outside the APK.
 *
 * [install] returns a cold flow. Implementations may do their work on a background
 * dispatcher, while Flow context preservation delivers events in the collector's
 * context. A UI collecting from its main scope can therefore update state directly.
 * Each collection ends with either [LocalModelInstallEvent.Installed] or
 * [LocalModelInstallEvent.Failed]. Cancelling the collection, [cancel], and [close]
 * terminate active work with a coroutine cancellation instead of a failure event.
 */
interface LocalModelInstaller : AutoCloseable {
    val isInstalled: Boolean

    fun install(): Flow<LocalModelInstallEvent>

    /** Cancels the active transfer without deleting resumable data. */
    fun cancel()

    override fun close() = cancel()
}

sealed interface LocalModelInstallEvent {
    /** Progress for one item in the complete offline bundle. */
    data class BundleProgress(
        val componentName: String,
        val componentIndex: Int,
        val componentCount: Int,
        val progress: Float,
        val detail: String,
    ) : LocalModelInstallEvent {
        init {
            require(componentName.isNotBlank())
            require(componentIndex in 1..componentCount)
            require(progress in 0f..1f)
            require(detail.isNotBlank())
        }
    }

    data class CheckingStorage(
        val requiredFreeBytes: Long,
        val availableFreeBytes: Long,
    ) : LocalModelInstallEvent

    /**
     * Aggregate progress over every model file. Existing resumable bytes are included
     * in [downloadedBytes], so the UI can show one stable progress indicator.
     */
    data class Downloading(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val fileDownloadedBytes: Long,
        val fileSizeBytes: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : LocalModelInstallEvent

    data class Verifying(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
    ) : LocalModelInstallEvent

    data object Activating : LocalModelInstallEvent

    data class Installed(
        val alreadyInstalled: Boolean,
    ) : LocalModelInstallEvent

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : LocalModelInstallEvent
}
