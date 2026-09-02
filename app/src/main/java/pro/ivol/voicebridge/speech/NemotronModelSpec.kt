package pro.ivol.voicebridge.speech

import android.content.Context
import java.io.File
import java.util.Locale

/** Immutable manifest for the separately downloaded NVIDIA ASR weights. */
object NemotronModelSpec {
    const val MODEL_ID =
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11"

    const val MODEL_REPOSITORY =
        "https://huggingface.co/csukuangfj2/$MODEL_ID"

    const val MODEL_REVISION = "ab43d895f5985b1bbab8b6eac8607fcdc05343f3"

    const val OFFICIAL_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "$MODEL_ID.tar.bz2"

    const val OFFICIAL_ARCHIVE_SHA256 =
        "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a"

    const val OFFICIAL_ARCHIVE_SIZE_BYTES = 475_271_763L

    const val READY_MARKER = ".voicebridge-ready"

    val files = listOf(
        ModelFile(
            name = "encoder.int8.onnx",
            sizeBytes = 657_601_403L,
            sha256 = "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
        ),
        ModelFile(
            name = "decoder.int8.onnx",
            sizeBytes = 14_978_075L,
            sha256 = "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
        ),
        ModelFile(
            name = "joiner.int8.onnx",
            sizeBytes = 9_504_438L,
            sha256 = "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
        ),
        ModelFile(
            name = "tokens.txt",
            sizeBytes = 131_440L,
            sha256 = "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a",
        ),
    )

    fun directory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "models/$MODEL_ID")

    fun isInstalled(context: Context): Boolean =
        isVerifiedDirectory(directory(context))

    fun isVerifiedDirectory(directory: File): Boolean {
        val marker = File(directory, READY_MARKER)
        return marker.isFile &&
            runCatching { marker.readText() }.getOrNull() == readyMarkerContent() &&
            files.all { modelFile ->
                File(directory, modelFile.name).let {
                    it.isFile && it.length() == modelFile.sizeBytes
                }
            }
    }

    fun readyMarkerContent(): String = buildString {
        append("revision=").append(MODEL_REVISION).append('\n')
        files.forEach { file ->
            append(file.name)
                .append('=')
                .append(file.sha256)
                .append(',')
                .append(file.sizeBytes)
                .append('\n')
        }
    }

    fun downloadUrl(file: ModelFile): String =
        "$MODEL_REPOSITORY/resolve/$MODEL_REVISION/${file.name}?download=true"

    fun languageHint(languageTag: String): String {
        val trimmed = languageTag.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
        val primaryCode = normalized.substringBefore('-').substringBefore('_')
        return when {
            normalized.isBlank() -> "auto"
            normalized == "auto" -> "auto"
            primaryCode == "ru" -> "ru-RU"
            primaryCode == "en" -> "en-US"
            // The exported encoder prompt_dictionary has vi-VN, but no bare vi key.
            primaryCode == "vi" -> "vi-VN"
            else -> trimmed
        }
    }

    data class ModelFile(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
    )
}
