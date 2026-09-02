package pro.ivol.voicebridge.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.ivol.voicebridge.domain.AppLanguage
import pro.ivol.voicebridge.domain.LocalModelInstallEvent

@OptIn(ExperimentalCoroutinesApi::class)
class MlKitTranslationModelInstallerTest {
    @Test
    fun `already downloaded model is verified and marked without downloading`() = runTest {
        val store = FakeModelStore(downloaded = true)
        val marker = FakeInstallationMarker(isInstalled = false)
        val installer = installer(store, marker)

        val events = installer.install().toList()

        assertEquals(1, store.verificationCalls)
        assertEquals(0, store.downloadCalls)
        assertTrue(marker.isInstalled)
        assertTrue(installer.isInstalled)
        assertTrue(events[0] is LocalModelInstallEvent.CheckingStorage)
        assertTrue(events[1] is LocalModelInstallEvent.Verifying)
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = true),
            events[2],
        )
    }

    @Test
    fun `fresh model download is verified marked and idempotent`() = runTest {
        val store = FakeModelStore(downloaded = false)
        val marker = FakeInstallationMarker(isInstalled = false)
        val installer = installer(store, marker)

        val firstEvents = installer.install().toList()
        val secondEvents = installer.install().toList()

        val downloading = firstEvents[1] as LocalModelInstallEvent.Downloading
        assertEquals(0L, downloading.totalBytes)
        assertEquals(0L, downloading.fileSizeBytes)
        assertTrue(firstEvents[2] is LocalModelInstallEvent.Verifying)
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = false),
            firstEvents[3],
        )
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = true),
            secondEvents.last(),
        )
        assertEquals(1, store.downloadCalls)
        assertEquals(3, store.verificationCalls)
        assertTrue(marker.isInstalled)
    }

    @Test
    fun `download failure emits Failed and does not set marker`() = runTest {
        val failure = IllegalStateException("network unavailable")
        val store = FakeModelStore(downloaded = false, downloadFailure = failure)
        val marker = FakeInstallationMarker(isInstalled = true)
        val installer = installer(store, marker)

        val events = installer.install().toList()

        assertEquals(3, events.size)
        assertTrue(events[0] is LocalModelInstallEvent.CheckingStorage)
        assertTrue(events[1] is LocalModelInstallEvent.Downloading)
        val failed = events[2] as LocalModelInstallEvent.Failed
        assertEquals(failure, failed.cause)
        assertEquals("network unavailable", failed.message)
        assertFalse(marker.isInstalled)
        assertFalse(installer.isInstalled)
    }

    @Test
    fun `cancelled Google task completion cannot verify mark or emit Installed`() = runTest {
        val store = PausingDownloadModelStore()
        val marker = FakeInstallationMarker(isInstalled = false)
        val installer = installer(store, marker)
        val events = mutableListOf<LocalModelInstallEvent>()

        val collection = launch {
            installer.install().collect(events::add)
        }
        store.downloadStarted.await()

        installer.cancel()
        store.finishDownload.complete(Unit)
        collection.join()

        assertTrue(collection.isCancelled)
        assertFalse(marker.isInstalled)
        assertEquals(1, store.verificationCalls)
        assertFalse(events.any { it is LocalModelInstallEvent.Verifying })
        assertFalse(events.any { it is LocalModelInstallEvent.Installed })

        // The manager-owned transfer is preserved. A new collection discovers
        // the completed model and marks it without starting a second download.
        val resumedEvents = installer.install().toList()
        assertEquals(1, store.downloadCalls)
        assertEquals(
            LocalModelInstallEvent.Installed(alreadyInstalled = true),
            resumedEvents.last(),
        )
        assertTrue(marker.isInstalled)
    }

    @Test
    fun `English model cannot be requested`() = runTest {
        var thrown: Throwable? = null
        try {
            MlKitTranslationModelInstaller(
                language = AppLanguage.ENGLISH,
                modelStore = FakeModelStore(downloaded = false),
                installationMarker = FakeInstallationMarker(false),
                workDispatcher = StandardTestDispatcher(testScheduler),
            )
        } catch (throwable: Throwable) {
            thrown = throwable
        }
        assertTrue(thrown is IllegalArgumentException)
    }

    private fun TestScope.installer(
        store: MlKitTranslationModelStore,
        marker: MlKitTranslationModelInstallationMarker,
    ) = MlKitTranslationModelInstaller(
        language = AppLanguage.VIETNAMESE,
        modelStore = store,
        installationMarker = marker,
        workDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeInstallationMarker(
        override var isInstalled: Boolean,
    ) : MlKitTranslationModelInstallationMarker

    private class FakeModelStore(
        private var downloaded: Boolean,
        private val downloadFailure: Throwable? = null,
    ) : MlKitTranslationModelStore {
        var verificationCalls = 0
        var downloadCalls = 0

        override suspend fun isModelDownloaded(): Boolean {
            verificationCalls += 1
            return downloaded
        }

        override suspend fun downloadModel() {
            downloadCalls += 1
            downloadFailure?.let { throw it }
            downloaded = true
        }
    }

    private class PausingDownloadModelStore : MlKitTranslationModelStore {
        val downloadStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        var verificationCalls = 0
        var downloadCalls = 0
        private var downloaded = false

        override suspend fun isModelDownloaded(): Boolean {
            verificationCalls += 1
            return downloaded
        }

        override suspend fun downloadModel() {
            downloadCalls += 1
            downloadStarted.complete(Unit)
            finishDownload.await()
            downloaded = true
        }
    }
}
