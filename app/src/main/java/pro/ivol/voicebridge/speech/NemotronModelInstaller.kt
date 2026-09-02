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

/**
 * Downloads and activates the separately distributed Nemotron weights.
 *
 * Android has no dependable built-in bzip2 decoder and this project intentionally has
 * no archive dependency. Instead of unpacking [NemotronModelSpec.OFFICIAL_ARCHIVE_URL],
 * the installer downloads the same four immutable model files directly from the pinned
 * upstream revision. Every file is checked against [NemotronModelSpec] before it can be
 * activated.
 */
class NemotronModelInstaller(context: Context) : LocalModelInstaller {
    private val appContext = context.applicationContext
    private val modelsDirectory = File(appContext.noBackupFilesDir, MODELS_DIRECTORY_NAME)
    private val finalDirectory = NemotronModelSpec.directory(appContext)
    private val stagingDirectory =
        File(modelsDirectory, ".${NemotronModelSpec.MODEL_ID}.staging")
    private val previousDirectory =
        File(modelsDirectory, ".${NemotronModelSpec.MODEL_ID}.previous")

    private val installMutex = Mutex()
    private val cancellationEpoch = AtomicLong(0L)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val closed = AtomicBoolean(false)

    override val isInstalled: Boolean
        get() = NemotronModelSpec.isVerifiedDirectory(finalDirectory)

    override fun install(): Flow<LocalModelInstallEvent> = flow {
        installMutex.withLock {
            if (isInstalled) {
                emit(LocalModelInstallEvent.Installed(alreadyInstalled = true))
                return@withLock
            }

            val operationEpoch = cancellationEpoch.get()
            try {
                check(!closed.get()) { "The local model installer is already closed." }
                installInternal(operationEpoch) { event -> emit(event) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                emit(
                    LocalModelInstallEvent.Failed(
                        message = throwable.message
                            ?: "Could not install the local NVIDIA model.",
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

        // A ready marker is only valid after every file has passed SHA-256 validation.
        File(stagingDirectory, NemotronModelSpec.READY_MARKER).deleteIfPresent()
        File(stagingDirectory, MARKER_TEMP_NAME).deleteIfPresent()
        ensureDirectory(stagingDirectory)

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

        val totalBytes = NemotronModelSpec.files.sumOf { it.sizeBytes }
        var completedBytes = 0L

        NemotronModelSpec.files.forEachIndexed { zeroBasedIndex, modelFile ->
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
                        fileCount = NemotronModelSpec.files.size,
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

        // Do not leave any resumable artifacts inside a successfully staged model.
        NemotronModelSpec.files.forEach { modelFile ->
            File(stagingDirectory, "${modelFile.name}$PART_SUFFIX").deleteIfPresent()
        }

        checkActive(operationEpoch)
        writeReadyMarkerLast()
        emitEvent(LocalModelInstallEvent.Activating)
        checkActive(operationEpoch)
        activateStagingDirectory()

        check(NemotronModelSpec.isVerifiedDirectory(finalDirectory)) {
            "The local model was activated but failed its final installation check."
        }
        emitEvent(LocalModelInstallEvent.Installed(alreadyInstalled = false))
    }

    private suspend fun downloadAndVerifyFile(
        modelFile: NemotronModelSpec.ModelFile,
        partialFile: File,
        completedFile: File,
        fileIndex: Int,
        completedBytes: Long,
        totalBytes: Long,
        operationEpoch: Long,
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
    ) {
        if (partialFile.length() > modelFile.sizeBytes) {
            partialFile.deleteOrThrow()
        }

        var failure: IOException? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            checkActive(operationEpoch)
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
                        fileCount = NemotronModelSpec.files.size,
                    ),
                )
                if (!verifyFile(partialFile, modelFile, operationEpoch)) {
                    partialFile.deleteOrThrow()
                    throw IOException("SHA-256 mismatch for ${modelFile.name}.")
                }

                moveAtomically(partialFile, completedFile)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                checkActive(operationEpoch)
                failure = io
                if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) {
                    delay(RETRY_DELAYS_MILLIS[attempt])
                }
            }
        }

        throw failure ?: IOException("Could not download ${modelFile.name}.")
    }

    private suspend fun downloadFileOnce(
        modelFile: NemotronModelSpec.ModelFile,
        partialFile: File,
        fileIndex: Int,
        completedBytes: Long,
        totalBytes: Long,
        operationEpoch: Long,
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
    ) {
        var resumeOffset = partialFile.length()
        emitDownloadProgress(
            emitEvent = emitEvent,
            modelFile = modelFile,
            fileIndex = fileIndex,
            fileDownloadedBytes = resumeOffset,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
        )

        if (resumeOffset == modelFile.sizeBytes) return

        var connection: HttpURLConnection? = null
        try {
            connection = openConnectionFollowingRedirects(
                initialUrl = NemotronModelSpec.downloadUrl(modelFile),
                resumeOffset = resumeOffset,
                expectedSize = modelFile.sizeBytes,
                operationEpoch = operationEpoch,
            )
            val responseCode = connection.responseCode
            val append = when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    validateContentRange(
                        contentRange = connection.getHeaderField("Content-Range"),
                        expectedStart = resumeOffset,
                        expectedSize = modelFile.sizeBytes,
                    )
                    true
                }

                HttpURLConnection.HTTP_OK -> {
                    // A server may ignore Range. Restart this one file safely.
                    resumeOffset = 0L
                    false
                }

                HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (resumeOffset == modelFile.sizeBytes) return
                    throw IOException(
                        "The server rejected resume for ${modelFile.name}.",
                    )
                }

                else -> throw IOException(
                    "The server returned HTTP $responseCode for ${modelFile.name}.",
                )
            }

            val expectedResponseBytes = modelFile.sizeBytes - resumeOffset
            val responseBytes = connection
                .getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?: -1L
            if (responseBytes >= 0L && responseBytes != expectedResponseBytes) {
                throw IOException(
                    "Invalid response size for ${modelFile.name}: " +
                        "$responseBytes instead of $expectedResponseBytes bytes.",
                )
            }

            BufferedInputStream(connection.inputStream, IO_BUFFER_SIZE).use { input ->
                FileOutputStream(partialFile, append).use { fileOutput ->
                    val output = BufferedOutputStream(fileOutput, IO_BUFFER_SIZE)
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var fileBytes = resumeOffset
                    var lastReportedBytes = resumeOffset
                    var lastReportedAt = System.nanoTime()

                    while (true) {
                        checkActive(operationEpoch)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (fileBytes + read > modelFile.sizeBytes) {
                            throw IOException(
                                "${modelFile.name} is larger than expected.",
                            )
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
        resumeOffset: Long,
        expectedSize: Long,
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
                if (resumeOffset > 0L) setRequestProperty("Range", "bytes=$resumeOffset-")
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

        throw IOException("Could not open the model file URL ($expectedSize bytes).")
    }

    private suspend fun emitDownloadProgress(
        emitEvent: suspend (LocalModelInstallEvent) -> Unit,
        modelFile: NemotronModelSpec.ModelFile,
        fileIndex: Int,
        fileDownloadedBytes: Long,
        completedBytes: Long,
        totalBytes: Long,
    ) {
        emitEvent(
            LocalModelInstallEvent.Downloading(
                fileName = modelFile.name,
                fileIndex = fileIndex,
                fileCount = NemotronModelSpec.files.size,
                fileDownloadedBytes = fileDownloadedBytes,
                fileSizeBytes = modelFile.sizeBytes,
                downloadedBytes = (completedBytes + fileDownloadedBytes).coerceAtMost(totalBytes),
                totalBytes = totalBytes,
            ),
        )
    }

    private suspend fun verifyFile(
        file: File,
        modelFile: NemotronModelSpec.ModelFile,
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
        val readyMarker = File(stagingDirectory, NemotronModelSpec.READY_MARKER)
        readyMarker.deleteIfPresent()
        temporaryMarker.deleteIfPresent()

        FileOutputStream(temporaryMarker, false).use { output ->
            output.write(NemotronModelSpec.readyMarkerContent().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        moveAtomically(temporaryMarker, readyMarker)
    }

    private fun activateStagingDirectory() {
        if (NemotronModelSpec.isVerifiedDirectory(finalDirectory)) return

        // Preserve an invalid old directory until the new staging directory is active.
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
            throw CancellationException("Local model installation was cancelled.")
        }
    }

    private fun validateContentRange(
        contentRange: String?,
        expectedStart: Long,
        expectedSize: Long,
    ) {
        val match = CONTENT_RANGE_REGEX.matchEntire(contentRange.orEmpty())
            ?: throw IOException("The server returned an invalid Content-Range.")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (
            start != expectedStart ||
            end == null ||
            end < expectedStart ||
            end >= expectedSize ||
            total != expectedSize
        ) {
            throw IOException("The server returned a range that does not match the model file.")
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
            "Not enough free space: at least ${formatGib(requiredBytes)} GiB is required; " +
                "${formatGib(availableBytes)} GiB is available.",
        )

    companion object {
        private const val MODELS_DIRECTORY_NAME = "models"
        private const val PART_SUFFIX = ".part"
        private const val MARKER_TEMP_NAME = ".voicebridge-ready.tmp"
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val MAX_REDIRECTS = 8
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val IO_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 1024L * 1024L
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val MINIMUM_FREE_BYTES = 3L * 1024L * 1024L * 1024L / 2L
        private const val USER_AGENT = "VoiceBridge-Android/0.5.1 NemotronModelInstaller"

        private val RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L)
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        private fun formatGib(bytes: Long): String =
            String.format(Locale.ROOT, "%.2f", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    }
}
