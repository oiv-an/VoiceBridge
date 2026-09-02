package pro.ivol.voicebridge.speech

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NemotronModelSpecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `nonempty model files are not activated without verified marker`() {
        val directory = createModelFiles()

        assertFalse(NemotronModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `matching versioned marker activates complete model`() {
        val directory = createModelFiles()
        File(directory, NemotronModelSpec.READY_MARKER)
            .writeText(NemotronModelSpec.readyMarkerContent())

        assertTrue(NemotronModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `marker from another manifest does not activate model`() {
        val directory = createModelFiles()
        File(directory, NemotronModelSpec.READY_MARKER).writeText("revision=other")

        assertFalse(NemotronModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `wrong model file size is rejected even with matching marker`() {
        val directory = createModelFiles()
        File(directory, "tokens.txt").writeText("wrong")
        File(directory, NemotronModelSpec.READY_MARKER)
            .writeText(NemotronModelSpec.readyMarkerContent())

        assertFalse(NemotronModelSpec.isVerifiedDirectory(directory))
    }

    @Test
    fun `Android locale tags map to Nemotron prompt language codes`() {
        assertEquals("ru-RU", NemotronModelSpec.languageHint("ru-RU"))
        assertEquals("vi-VN", NemotronModelSpec.languageHint("vi-VN"))
        assertEquals("vi-VN", NemotronModelSpec.languageHint("vi"))
        assertEquals("en-US", NemotronModelSpec.languageHint("en_US"))
        assertEquals("auto", NemotronModelSpec.languageHint(""))
    }

    private fun createModelFiles(): File = temporaryFolder.newFolder("model").also { directory ->
        NemotronModelSpec.files.forEach { modelFile ->
            RandomAccessFile(File(directory, modelFile.name), "rw").use { file ->
                file.setLength(modelFile.sizeBytes)
            }
        }
    }
}
