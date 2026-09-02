package pro.ivol.voicebridge.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.ivol.voicebridge.domain.LocalModelInstallEvent
import pro.ivol.voicebridge.domain.LocalModelInstaller

class CombinedLocalModelInstallerTest {
    @Test
    fun `installs all four components in order with monotonic aggregate progress`() = runTest {
        val callOrder = mutableListOf<String>()
        val installers = componentInstallers(callOrder)
        val combined = combined(installers)

        val events = combined.install().toList()

        assertEquals(
            listOf("nemotron", "vietnamese", "russian translation", "vietnamese translation"),
            callOrder,
        )
        assertTrue(combined.isInstalled)
        assertEquals(
            listOf(LocalModelInstallEvent.Installed(alreadyInstalled = false)),
            events.filterIsInstance<LocalModelInstallEvent.Installed>(),
        )
        assertTrue(events.last() is LocalModelInstallEvent.Installed)
        assertTrue(
            events.all {
                it is LocalModelInstallEvent.BundleProgress ||
                    it is LocalModelInstallEvent.Installed
            },
        )

        val progress = events.filterIsInstance<LocalModelInstallEvent.BundleProgress>()
        assertEquals(
            listOf(
                "Russian speech · NVIDIA",
                "Vietnamese speech",
                "Russian offline translation",
                "Vietnamese offline translation",
            ),
            progress.map { it.componentName }.distinct(),
        )
        assertEquals(0f, progress.first().progress)
        assertEquals(1f, progress.last().progress)
        assertTrue(progress.zipWithNext().all { (before, after) ->
            before.progress <= after.progress
        })
        assertTrue(progress.all { it.componentCount == 4 })
    }

    @Test
    fun `installed speech is skipped while cached translation models are revalidated`() = runTest {
        val callOrder = mutableListOf<String>()
        val installers = componentInstallers(
            callOrder = callOrder,
            initiallyInstalled = setOf(0, 2),
        )
        val combined = combined(installers)

        val events = combined.install().toList()

        assertEquals(
            listOf("vietnamese", "russian translation", "vietnamese translation"),
            callOrder,
        )
        assertEquals(listOf(0, 1, 1, 1), installers.map { it.installCalls })
        assertTrue(combined.isInstalled)
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = false),
            events.last(),
        )

        val progress = events.filterIsInstance<LocalModelInstallEvent.BundleProgress>()
        assertTrue((1..4).all { componentIndex ->
            progress.any {
                it.componentIndex == componentIndex &&
                    it.detail == "Ready"
            }
        })
        assertTrue(progress.zipWithNext().all { (before, after) ->
            before.progress <= after.progress
        })
    }

    @Test
    fun `stale fully marked bundle reports fresh work after ML Kit validation`() = runTest {
        val callOrder = mutableListOf<String>()
        val installers = componentInstallers(
            callOrder = callOrder,
            initiallyInstalled = setOf(0, 1, 2, 3),
        )
        val combined = combined(installers)

        val events = combined.install().toList()

        assertEquals(listOf("russian translation", "vietnamese translation"), callOrder)
        assertEquals(listOf(0, 0, 1, 1), installers.map { it.installCalls })
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = false),
            events.last(),
        )
    }

    @Test
    fun `failure is forwarded and stops every remaining component`() = runTest {
        val callOrder = mutableListOf<String>()
        val failure = LocalModelInstallEvent.Failed("download failed")
        val installers = componentInstallers(callOrder).toMutableList()
        installers[1] = FakeInstaller(
            name = "vietnamese",
            callOrder = callOrder,
            events = listOf(
                checkingStorage(),
                failure,
                LocalModelInstallEvent.Installed(alreadyInstalled = false),
            ),
        )
        val combined = combined(installers)

        val events = combined.install().toList()

        assertEquals(listOf("nemotron", "vietnamese"), callOrder)
        assertEquals(0, installers[2].installCalls)
        assertEquals(0, installers[3].installCalls)
        assertEquals(failure, events.last())
        assertFalse(events.any { it is LocalModelInstallEvent.Installed })
        assertFalse(combined.isInstalled)
    }

    @Test
    fun `cancel and close reach every component even when one throws`() {
        val installers = listOf(
            FakeInstaller(name = "nemotron", cancelFailure = IllegalStateException("cancel")),
            FakeInstaller(name = "vietnamese"),
            FakeInstaller(
                name = "russian translation",
                closeFailure = IllegalStateException("close"),
            ),
            FakeInstaller(name = "vietnamese translation"),
        )
        val combined = combined(installers)

        assertThrows(IllegalStateException::class.java) { combined.cancel() }
        assertEquals(listOf(1, 1, 1, 1), installers.map { it.cancelCalls })

        assertThrows(IllegalStateException::class.java) { combined.close() }
        assertEquals(listOf(1, 1, 1, 1), installers.map { it.closeCalls })

        // close is idempotent even if the first propagation reported an error.
        combined.close()
        assertEquals(listOf(1, 1, 1, 1), installers.map { it.closeCalls })
    }

    private fun componentInstallers(
        callOrder: MutableList<String>,
        initiallyInstalled: Set<Int> = emptySet(),
    ): List<FakeInstaller> = listOf(
        FakeInstaller(
            name = "nemotron",
            initiallyInstalled = 0 in initiallyInstalled,
            callOrder = callOrder,
        ),
        FakeInstaller(
            name = "vietnamese",
            initiallyInstalled = 1 in initiallyInstalled,
            callOrder = callOrder,
        ),
        FakeInstaller(
            name = "russian translation",
            initiallyInstalled = 2 in initiallyInstalled,
            callOrder = callOrder,
        ),
        FakeInstaller(
            name = "vietnamese translation",
            initiallyInstalled = 3 in initiallyInstalled,
            callOrder = callOrder,
        ),
    )

    private fun combined(installers: List<FakeInstaller>) = CombinedLocalModelInstaller(
        nemotronInstaller = installers[0],
        vietnameseInstaller = installers[1],
        russianTranslationInstaller = installers[2],
        vietnameseTranslationInstaller = installers[3],
    )

    private class FakeInstaller(
        private val name: String,
        initiallyInstalled: Boolean = false,
        private val callOrder: MutableList<String>? = null,
        private val events: List<LocalModelInstallEvent> = successfulEvents(),
        private val cancelFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : LocalModelInstaller {
        override var isInstalled: Boolean = initiallyInstalled
            private set

        var installCalls = 0
            private set
        var cancelCalls = 0
            private set
        var closeCalls = 0
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

        override fun cancel() {
            cancelCalls += 1
            cancelFailure?.let { throw it }
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        fun successfulEvents(): List<LocalModelInstallEvent> = listOf(
            checkingStorage(),
            LocalModelInstallEvent.Downloading(
                fileName = "model.bin",
                fileIndex = 1,
                fileCount = 1,
                fileDownloadedBytes = 50L,
                fileSizeBytes = 100L,
                downloadedBytes = 50L,
                totalBytes = 100L,
            ),
            LocalModelInstallEvent.Downloading(
                fileName = "model.bin",
                fileIndex = 1,
                fileCount = 1,
                fileDownloadedBytes = 100L,
                fileSizeBytes = 100L,
                downloadedBytes = 100L,
                totalBytes = 100L,
            ),
            LocalModelInstallEvent.Verifying(
                fileName = "model.bin",
                fileIndex = 1,
                fileCount = 1,
            ),
            LocalModelInstallEvent.Activating,
            LocalModelInstallEvent.Installed(alreadyInstalled = false),
        )

        fun checkingStorage() = LocalModelInstallEvent.CheckingStorage(
            requiredFreeBytes = 100L,
            availableFreeBytes = 1_000L,
        )
    }
}
