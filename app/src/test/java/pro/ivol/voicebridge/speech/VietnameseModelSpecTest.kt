package pro.ivol.voicebridge.speech

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VietnameseModelSpecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `model files are not active without the verified marker`() {
        val directory = createModelFiles()

        assertFalse(VietnameseModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `matching versioned marker activates a complete model`() {
        val directory = createModelFiles()
        File(directory, VietnameseModelSpec.READY_MARKER)
            .writeText(VietnameseModelSpec.readyMarkerContent())

        assertTrue(VietnameseModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `marker from another revision is rejected`() {
        val directory = createModelFiles()
        File(directory, VietnameseModelSpec.READY_MARKER)
            .writeText("revision=another-revision\n")

        assertFalse(VietnameseModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `wrong file size is rejected even with a matching marker`() {
        val directory = createModelFiles()
        RandomAccessFile(File(directory, "tokens.txt"), "rw").use { file ->
            file.setLength(1L)
        }
        File(directory, VietnameseModelSpec.READY_MARKER)
            .writeText(VietnameseModelSpec.readyMarkerContent())

        assertFalse(VietnameseModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `pinned runtime manifest has unique safe files and expected total size`() {
        val files = VietnameseModelSpec.files

        assertEquals(4, files.size)
        assertEquals(files.size, files.map { it.name }.distinct().size)
        assertEquals(33_920_802L, files.sumOf { it.sizeBytes })
        files.forEach { modelFile ->
            assertEquals(File(modelFile.name).name, modelFile.name)
            assertTrue(modelFile.name.isNotBlank())
            assertTrue(modelFile.sizeBytes > 0L)
            assertTrue(SHA_256_REGEX.matches(modelFile.sha256))
        }
    }

    @Test
    fun `every download URL is HTTPS and pinned to the manifest revision`() {
        VietnameseModelSpec.files.forEach { modelFile ->
            val url = VietnameseModelSpec.downloadUrl(modelFile)

            assertTrue(url.startsWith("https://"))
            assertTrue(url.contains("/${VietnameseModelSpec.MODEL_REVISION}/"))
            assertTrue(url.contains("/${modelFile.name}"))
        }
    }

    @Test
    fun `ready marker contains the pinned revision and every checksum and size`() {
        val marker = VietnameseModelSpec.readyMarkerContent()

        assertTrue(marker.startsWith("revision=${VietnameseModelSpec.MODEL_REVISION}\n"))
        VietnameseModelSpec.files.forEach { modelFile ->
            assertTrue(
                marker.contains(
                    "${modelFile.name}=${modelFile.sha256},${modelFile.sizeBytes}\n",
                ),
            )
        }
    }

    private fun createModelFiles(): File = temporaryFolder.newFolder("vietnamese-model")
        .also { directory ->
            VietnameseModelSpec.files.forEach { modelFile ->
                RandomAccessFile(File(directory, modelFile.name), "rw").use { file ->
                    file.setLength(modelFile.sizeBytes)
                }
            }
        }

    private companion object {
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}
