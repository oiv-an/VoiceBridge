package pro.ivol.voicebridge.screen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller

class ScreenTranslationModelInstallerTest {
    @Test
    fun `installs Russian then Vietnamese with one terminal success`() = runTest {
        val callOrder = mutableListOf<String>()
        val russian = FakeInstaller("russian", callOrder = callOrder)
        val vietnamese = FakeInstaller("vietnamese", callOrder = callOrder)
        val installer = ScreenTranslationModelInstaller(russian, vietnamese)

        val events = installer.install().toList()

        assertEquals(listOf("russian", "vietnamese"), callOrder)
        assertTrue(installer.isInstalled)
        assertEquals(
            listOf(LocalModelInstallEvent.Installed(alreadyInstalled = false)),
            events.filterIsInstance<LocalModelInstallEvent.Installed>(),
        )
        assertTrue(events.last() is LocalModelInstallEvent.Installed)

        val progress = events.filterIsInstance<LocalModelInstallEvent.BundleProgress>()
        assertEquals(
            listOf("Russian offline translation", "Vietnamese offline translation"),
            progress.map { it.componentName }.distinct(),
        )
        assertTrue(progress.all { it.componentCount == 2 })
        assertTrue(progress.zipWithNext().all { (before, after) ->
            before.progress <= after.progress
        })
        assertEquals(1f, progress.last().progress)
    }

    @Test
    fun `success preserves already installed only when both models validate from cache`() = runTest {
        val russian = FakeInstaller(
            name = "russian",
            initiallyInstalled = true,
            events = successfulEvents(alreadyInstalled = true),
        )
        val vietnamese = FakeInstaller(
            name = "vietnamese",
            initiallyInstalled = true,
            events = successfulEvents(alreadyInstalled = true),
        )
        val installer = ScreenTranslationModelInstaller(russian, vietnamese)

        val events = installer.install().toList()

        assertEquals(1, russian.installCalls)
        assertEquals(1, vietnamese.installCalls)
        assertEquals(LocalModelInstallEvent.Installed(alreadyInstalled = true), events.last())
    }

    @Test
    fun `Russian failure is forwarded and Vietnamese is not started`() = runTest {
        val callOrder = mutableListOf<String>()
        val failure = LocalModelInstallEvent.Failed("russian model unavailable")
        val russian = FakeInstaller(
            name = "russian",
            callOrder = callOrder,
            events = listOf(failure),
        )
        val vietnamese = FakeInstaller("vietnamese", callOrder = callOrder)
        val installer = ScreenTranslationModelInstaller(russian, vietnamese)

        val events = installer.install().toList()

        assertEquals(listOf("russian"), callOrder)
        assertEquals(0, vietnamese.installCalls)
        assertEquals(failure, events.last())
        assertFalse(events.any { it is LocalModelInstallEvent.Installed })
        assertFalse(installer.isInstalled)
    }

    private class FakeInstaller(
        private val name: String,
        initiallyInstalled: Boolean = false,
        private val callOrder: MutableList<String>? = null,
        private val events: List<LocalModelInstallEvent> = successfulEvents(),
    ) : LocalModelInstaller {
        override var isInstalled: Boolean = initiallyInstalled
            private set

        var installCalls = 0
            private set

        override fun install(): Flow<LocalModelInstallEvent> = flow {
            installCalls += 1
            callOrder?.add(name)
            events.forEach { event ->
                emit(event)
                if (event is LocalModelInstallEvent.Installed) {
                    isInstalled = true
                }
            }
        }

        override fun cancel() = Unit
    }

    private companion object {
        fun successfulEvents(
            alreadyInstalled: Boolean = false,
        ): List<LocalModelInstallEvent> = listOf(
            LocalModelInstallEvent.CheckingStorage(
                requiredFreeBytes = 0L,
                availableFreeBytes = 0L,
            ),
            LocalModelInstallEvent.Downloading(
                fileName = "model",
                fileIndex = 1,
                fileCount = 1,
                fileDownloadedBytes = 0L,
                fileSizeBytes = 0L,
                downloadedBytes = 0L,
                totalBytes = 0L,
            ),
            LocalModelInstallEvent.Verifying(
                fileName = "model",
                fileIndex = 1,
                fileCount = 1,
            ),
            LocalModelInstallEvent.Installed(alreadyInstalled = alreadyInstalled),
        )
    }
}
