package pro.ivol.voicebridge.speech

import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller

/** Downloads, verifies, and atomically activates the compact Vietnamese ASR model. */
class VietnameseModelInstaller(context: Context) : LocalModelInstaller {
    private val appContext = context.applicationContext
    private val modelsDirectory = File(appContext.noBackupFilesDir, MODELS_DIRECTORY_NAME)
    private val finalDirectory = VietnameseModelSpec.directory(appContext)
    private val stagingDirectory =
        File(modelsDirectory, ".${VietnameseModelSpec.MODEL_ID}.staging")
    private val previousDirectory =
        File(modelsDirectory, ".${VietnameseModelSpec.MODEL_ID}.previous")

    private val installMutex = Mutex()
    private val cancellationEpoch = AtomicLong(0L)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val closed = AtomicBoolean(false)

    override val isInstalled: Boolean
        get() = VietnameseModelSpec.isVerifiedDirectory(finalDirectory)

    override fun install(): Flow<LocalModelInstallEvent> = flow {
        installMutex.withLock {
            if (isInstalled) {
                emit(LocalModelInstallEvent.Installed(alreadyInstalled = true))
                return@withLock
            }

            val operationEpoch = cancellationEpoch.get()
            try {
                check(!closed.get()) { "The Vietnamese model installer is already closed." }
                installInternal(operationEpoch) { event -> emit(event) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                emit(
                    LocalModelInstallEvent.Failed(
                        message = throwable.message
                            ?: "Could not install the Vietnamese speech model.",
                        cause = throwable,
                    ),
                )
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        cancellationEpoch.incrementAndGet()
        activeConnection.getAndSet(null)?.disconnect()
    }

    override fun close() {
        closed.set(true)
        cancel()
    }

    private suspend fun installInternal(
        operationEpoch: Long,
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
    ) {
        checkActive(operationEpoch)
        ensureDirectory(modelsDirectory)

        ensureDirectory(stagingDirectory)
        // The marker is valid only after every pinned file has passed SHA-256 validation.
        File(stagingDirectory, VietnameseModelSpec.READY_MARKER).deleteIfPresent()
        File(stagingDirectory, MARKER_TEMP_NAME).deleteIfPresent()

        val availableBytes = StatFs(modelsDirectory.absolutePath).availableBytes
        emitEvent(
            LocalModelInstallEvent.CheckingStorage(
                requiredFreeBytes = MINIMUM_FREE_BYTES,
                availableFreeBytes = availableBytes,
            ),
        )
        if (availableBytes < MINIMUM_FREE_BYTES) {
            throw InsufficientStorageException(
                requiredBytes = MINIMUM_FREE_BYTES,
                availableBytes = availableBytes,
            )
        }

        val totalBytes = VietnameseModelSpec.files.sumOf { it.sizeBytes }
        var completedBytes = 0L

        VietnameseModelSpec.files.forEachIndexed { zeroBasedIndex, modelFile ->
            checkActive(operationEpoch)
            validateModelFileName(modelFile.name)
            val fileIndex = zeroBasedIndex + 1
            val completedFile = File(stagingDirectory, modelFile.name)
            val partialFile = File(stagingDirectory, "${modelFile.name}$PART_SUFFIX")

            if (completedFile.exists()) {
                emitEvent(
                    LocalModelInstallEvent.Verifying(
                        fileName = modelFile.name,
                        fileIndex = fileIndex,
                        fileCount = VietnameseModelSpec.files.size,
                    ),
                )
                if (verifyFile(completedFile, modelFile, operationEpoch)) {
                    partialFile.deleteIfPresent()
                    completedBytes += modelFile.sizeBytes
                    emitDownloadProgress(
                        emitEvent = emitEvent,
                        modelFile = modelFile,
                        fileIndex = fileIndex,
                        fileDownloadedBytes = modelFile.sizeBytes,
                        completedBytes = completedBytes - modelFile.sizeBytes,
                        totalBytes = totalBytes,
                    )
                    return@forEachIndexed
                }
                completedFile.deleteOrThrow()
            }

            downloadAndVerifyFile(
                modelFile = modelFile,
                partialFile = partialFile,
                completedFile = completedFile,
                fileIndex = fileIndex,
                completedBytes = completedBytes,
                totalBytes = totalBytes,
                operationEpoch = operationEpoch,
                emitEvent = emitEvent,
            )
            completedBytes += modelFile.sizeBytes
        }

        VietnameseModelSpec.files.forEach { modelFile ->
            File(stagingDirectory, "${modelFile.name}$PART_SUFFIX").deleteIfPresent()
        }

        checkActive(operationEpoch)
        writeReadyMarkerLast()
        emitEvent(LocalModelInstallEvent.Activating)
        checkActive(operationEpoch)
        activateStagingDirectory()
        checkActive(operationEpoch)

        check(VietnameseModelSpec.isVerifiedDirectory(finalDirectory)) {
            "The Vietnamese model was activated but failed its final installation check."
        }
        emitEvent(LocalModelInstallEvent.Installed(alreadyInstalled = false))
    }

    private suspend fun downloadAndVerifyFile(
        modelFile: VietnameseModelSpec.ModelFile,
        partialFile: File,
        completedFile: File,
        fileIndex: Int,
        completedBytes: Long,
        totalBytes: Long,
        operationEpoch: Long,
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
    ) {
        var failure: IOException? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            checkActive(operationEpoch)
            // These files are small; a clean per-file retry is safer than trusting
            // a server-specific Range implementation.
            partialFile.deleteIfPresent()
            try {
                downloadFileOnce(
                    modelFile = modelFile,
                    partialFile = partialFile,
                    fileIndex = fileIndex,
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                    operationEpoch = operationEpoch,
                    emitEvent = emitEvent,
                )

                emitEvent(
                    LocalModelInstallEvent.Verifying(
                        fileName = modelFile.name,
                        fileIndex = fileIndex,
                        fileCount = VietnameseModelSpec.files.size,
                    ),
                )
                if (!verifyFile(partialFile, modelFile, operationEpoch)) {
                    partialFile.deleteOrThrow()
                    throw IOException("SHA-256 mismatch for ${modelFile.name}.")
                }

                checkActive(operationEpoch)
                moveAtomically(partialFile, completedFile)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                checkActive(operationEpoch)
                failure = io
                partialFile.deleteIfPresent()
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                    delay(RETRY_DELAYS_MILLIS[attempt])
                }
            }
        }

        throw failure ?: IOException("Could not download ${modelFile.name}.")
    }

    private suspend fun downloadFileOnce(
        modelFile: VietnameseModelSpec.ModelFile,
        partialFile: File,
        fileIndex: Int,
        completedBytes: Long,
        totalBytes: Long,
        operationEpoch: Long,
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
    ) {
        emitDownloadProgress(
            emitEvent = emitEvent,
            modelFile = modelFile,
            fileIndex = fileIndex,
            fileDownloadedBytes = 0L,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
        )

        var connection: HttpURLConnection? = null
        try {
            connection = openConnectionFollowingRedirects(
                initialUrl = VietnameseModelSpec.downloadUrl(modelFile),
                operationEpoch = operationEpoch,
            )
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    "The server returned HTTP $responseCode for ${modelFile.name}.",
                )
            }

            val responseBytes = connection
                .getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?: -1L
            if (responseBytes >= 0L && responseBytes != modelFile.sizeBytes) {
                throw IOException(
                    "Invalid response size for ${modelFile.name}: " +
                        "$responseBytes instead of ${modelFile.sizeBytes} bytes.",
                )
            }

            BufferedInputStream(connection.inputStream, IO_BUFFER_SIZE).use { input ->
                FileOutputStream(partialFile, false).use { fileOutput ->
                    val output = BufferedOutputStream(fileOutput, IO_BUFFER_SIZE)
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var fileBytes = 0L
                    var lastReportedBytes = 0L
                    var lastReportedAt = System.nanoTime()

                    while (true) {
                        checkActive(operationEpoch)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (fileBytes + read > modelFile.sizeBytes) {
                            throw IOException("${modelFile.name} is larger than expected.")
                        }
                        output.write(buffer, 0, read)
                        fileBytes += read

                        val now = System.nanoTime()
                        if (
                            fileBytes == modelFile.sizeBytes ||
                            fileBytes - lastReportedBytes >= PROGRESS_STEP_BYTES ||
                            now - lastReportedAt >= PROGRESS_INTERVAL_NANOS
                        ) {
                            emitDownloadProgress(
                                emitEvent = emitEvent,
                                modelFile = modelFile,
                                fileIndex = fileIndex,
                                fileDownloadedBytes = fileBytes,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                            )
                            lastReportedBytes = fileBytes
                            lastReportedAt = now
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        } catch (io: IOException) {
            checkActive(operationEpoch)
            throw io
        } finally {
            if (connection != null) {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
            }
        }

        if (partialFile.length() != modelFile.sizeBytes) {
            throw IOException(
                "${modelFile.name} was not fully downloaded: " +
                    "${partialFile.length()} of ${modelFile.sizeBytes} bytes.",
            )
        }
    }

    private suspend fun openConnectionFollowingRedirects(
        initialUrl: String,
        operationEpoch: Long,
    ): HttpURLConnection {
        var currentUrl = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            checkHttps(currentUrl)
            checkActive(operationEpoch)

            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            activeConnection.set(connection)

            val responseCode = try {
                connection.responseCode
            } catch (io: IOException) {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                checkActive(operationEpoch)
                throw io
            }

            if (responseCode !in REDIRECT_CODES) return connection

            val location = connection.getHeaderField("Location")
                ?: run {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                    throw IOException("The server returned a redirect without a location.")
                }
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            if (redirectCount == MAX_REDIRECTS) {
                throw IOException("Too many redirects while downloading the model.")
            }
            currentUrl = URL(currentUrl, location)
        }

        throw IOException("Could not open the Vietnamese model file URL.")
    }

    private suspend fun emitDownloadProgress(
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
        modelFile: VietnameseModelSpec.ModelFile,
        fileIndex: Int,
        fileDownloadedBytes: Long,
        completedBytes: Long,
        totalBytes: Long,
    ) {
        emitEvent(
            LocalModelInstallEvent.Downloading(
                fileName = modelFile.name,
                fileIndex = fileIndex,
                fileCount = VietnameseModelSpec.files.size,
                fileDownloadedBytes = fileDownloadedBytes,
                fileSizeBytes = modelFile.sizeBytes,
                downloadedBytes = (completedBytes + fileDownloadedBytes).coerceAtMost(totalBytes),
                totalBytes = totalBytes,
            ),
        )
    }

    private suspend fun verifyFile(
        file: File,
        modelFile: VietnameseModelSpec.ModelFile,
        operationEpoch: Long,
    ): Boolean {
        if (!file.isFile || file.length() != modelFile.sizeBytes) return false

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(IO_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                checkActive(operationEpoch)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        }
        return actual == modelFile.sha256
    }

    private fun writeReadyMarkerLast() {
        val temporaryMarker = File(stagingDirectory, MARKER_TEMP_NAME)
        val readyMarker = File(stagingDirectory, VietnameseModelSpec.READY_MARKER)
        readyMarker.deleteIfPresent()
        temporaryMarker.deleteIfPresent()

        FileOutputStream(temporaryMarker, false).use { output ->
            output.write(VietnameseModelSpec.readyMarkerContent().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        moveAtomically(temporaryMarker, readyMarker)
    }

    private fun activateStagingDirectory() {
        if (VietnameseModelSpec.isVerifiedDirectory(finalDirectory)) return

        if (previousDirectory.exists()) previousDirectory.deleteTreeOrThrow()
        val hadPrevious = finalDirectory.exists()
        if (hadPrevious) moveAtomically(finalDirectory, previousDirectory)

        try {
            moveAtomically(stagingDirectory, finalDirectory)
        } catch (throwable: Throwable) {
            if (hadPrevious && previousDirectory.exists() && !finalDirectory.exists()) {
                runCatching { moveAtomically(previousDirectory, finalDirectory) }
            }
            throw throwable
        }

        if (previousDirectory.exists()) previousDirectory.deleteTreeOrThrow()
    }

    private suspend fun checkActive(operationEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (closed.get() || cancellationEpoch.get() != operationEpoch) {
            throw CancellationException("Vietnamese model installation was cancelled.")
        }
    }

    private fun validateModelFileName(name: String) {
        require(name.isNotBlank() && name == File(name).name && '/' !in name && '\\' !in name) {
            "Invalid model file name: $name"
        }
    }

    private fun checkHttps(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("The model may only be downloaded over HTTPS.")
        }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw IOException("Could not create directory ${directory.absolutePath}.")
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        if (destination.exists()) {
            throw IOException("Destination already exists: ${destination.absolutePath}.")
        }
        try {
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (throwable: Throwable) {
            throw IOException(
                "Could not atomically move ${source.name} to ${destination.name}.",
                throwable,
            )
        }
    }

    private fun File.deleteIfPresent() {
        if (exists() && !delete()) {
            throw IOException("Could not delete temporary file $absolutePath.")
        }
    }

    private fun File.deleteOrThrow() {
        if (!delete()) throw IOException("Could not delete file $absolutePath.")
    }

    private fun File.deleteTreeOrThrow() {
        val canonicalModelsDirectory = modelsDirectory.canonicalFile
        val canonicalTarget = canonicalFile
        if (canonicalTarget.parentFile != canonicalModelsDirectory) {
            throw IOException("Refusing to delete a path outside the models directory.")
        }
        if (!deleteRecursively()) {
            throw IOException("Could not delete old model directory $absolutePath.")
        }
    }

    private class InsufficientStorageException(requiredBytes: Long, availableBytes: Long) :
        IOException(
            "Not enough free space: at least ${formatMib(requiredBytes)} MB is required; " +
                "${formatMib(availableBytes)} MB is available.",
        )

    private companion object {
        const val MODELS_DIRECTORY_NAME = "models"
        const val PART_SUFFIX = ".part"
        const val MARKER_TEMP_NAME = ".voicebridge-ready.tmp"
        const val MAX_DOWNLOAD_ATTEMPTS = 3
        const val MAX_REDIRECTS = 8
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val IO_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_STEP_BYTES = 512L * 1024L
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        const val MINIMUM_FREE_BYTES = 128L * 1024L * 1024L
        const val USER_AGENT = "VoiceBridge-Android/0.5.1 VietnameseModelInstaller"

        val RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L)
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun formatMib(bytes: Long): String =
            String.format(Locale.ROOT, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))
    }
}
