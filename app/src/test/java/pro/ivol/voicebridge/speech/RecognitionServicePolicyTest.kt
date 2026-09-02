package pro.ivol.voicebridge.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionServicePolicyTest {
    @Test
    fun `Google app service wins over TTS and Huawei fake`() {
        val selected = RecognitionServicePolicy.select(
            listOf(
                candidate(
                    packageName = "com.huawei.hiassistantoversea",
                    className = "com.huawei.voice.FakeRecognitionService",
                ),
                candidate(
                    packageName = "com.google.android.tts",
                    className = "com.google.tts.GoogleTTSRecognitionService",
                ),
                candidate(
                    packageName = "com.google.android.googlequicksearchbox",
                    className = "com.google.voicesearch.GoogleRecognitionService",
                ),
            ),
        )

        assertEquals("com.google.android.googlequicksearchbox", selected?.packageName)
    }

    @Test
    fun `Google TTS is selected only when it is present`() {
        val tts = candidate(
            packageName = "com.google.android.tts",
            className = "com.google.tts.GoogleTTSRecognitionService",
            permission = null,
        )

        assertEquals(tts, RecognitionServicePolicy.select(listOf(tts)))
        assertNull(RecognitionServicePolicy.select(emptyList()))
    }

    @Test
    fun `fake service is never selected`() {
        assertNull(
            RecognitionServicePolicy.select(
                listOf(
                    candidate(
                        packageName = "com.huawei.hiassistantoversea",
                        className = "com.huawei.voice.FakeRecognitionService",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `disabled unexported and unprotected services are rejected`() {
        assertNull(
            RecognitionServicePolicy.select(
                listOf(
                    candidate(packageName = "system.disabled", serviceEnabled = false),
                    candidate(packageName = "system.hidden", exported = false),
                    candidate(packageName = "system.unprotected", permission = null),
                ),
            ),
        )
    }

    @Test
    fun `valid system service is the safe non Google fallback`() {
        val systemService = candidate(packageName = "vendor.speech")
        val untrustedService = candidate(
            packageName = "third.party.speech",
            systemApp = false,
        )

        assertEquals(
            systemService,
            RecognitionServicePolicy.select(listOf(untrustedService, systemService)),
        )
    }

    private fun candidate(
        packageName: String,
        className: String = "$packageName.RecognitionService",
        permission: String? = "android.permission.BIND_SPEECH_RECOGNITION",
        exported: Boolean = true,
        serviceEnabled: Boolean = true,
        applicationEnabled: Boolean = true,
        systemApp: Boolean = true,
    ) = SpeechServiceCandidate(
        packageName = packageName,
        className = className,
        permission = permission,
        exported = exported,
        serviceEnabled = serviceEnabled,
        applicationEnabled = applicationEnabled,
        systemApp = systemApp,
    )
}
