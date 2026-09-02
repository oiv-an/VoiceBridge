package pro.ivol.voicebridge.speech

import android.content.Context
import java.io.File

/** Immutable manifest for the Vietnamese Zipformer runtime files. */
object VietnameseModelSpec {
    const val MODEL_ID =
        "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"

    const val MODEL_REPOSITORY =
        "https://huggingface.co/csukuangfj2/$MODEL_ID"

    const val MODEL_REVISION = "83e140db6d23fbb8480fd5fb868f74ab80e7092c"

    const val OFFICIAL_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "$MODEL_ID.tar.bz2"

    const val OFFICIAL_ARCHIVE_SHA256 =
        "da8b637947091829d7ee9eda23da2a4ec7caa399233a3f4e34eb719fb2ea6b9b"

    const val OFFICIAL_ARCHIVE_SIZE_BYTES = 26_442_384L

    const val READY_MARKER = ".voicebridge-ready"

    /** bpe.model is intentionally absent: sherpa-onnx needs only these four files at runtime. */
    val files = listOf(
        ModelFile(
            name = "encoder.int8.onnx",
            sizeBytes = 27_699_063L,
            sha256 = "8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68",
        ),
        ModelFile(
            name = "decoder.onnx",
            sizeBytes = 5_165_084L,
            sha256 = "cf2aa385b82c9d5d40cd29c3188af52d0249b3b78f0d4b7eb84ad502d50c7e7f",
        ),
        ModelFile(
            name = "joiner.int8.onnx",
            sizeBytes = 1_033_417L,
            sha256 = "7311d2e17b810ecea515d79c71cc4668af8759256a06fa01d27047772320c821",
        ),
        ModelFile(
            name = "tokens.txt",
            sizeBytes = 23_238L,
            sha256 = "ca8171f8bbd516c050b627582f2125c8f5f1f6ed967ab41b0fa9aae2cf61b492",
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

    data class ModelFile(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
    )
}
