package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.SemVer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateManagerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `checking an available release does not alter unrelated application state`() = runBlocking {
        val release = release()
        val provider = FakeProvider(ReleaseCheckResult.UpdateAvailable(release, "etag-1"))
        val manager = UpdateManager(provider, FakeStager(tempDir.resolve("installer.exe")))
        try {
            manager.check(manual = false).join()

            val available = assertIs<UpdateState.Available>(manager.state.value)
            assertEquals(release.version, available.release.version)
            assertEquals(1, provider.calls.get())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `download progress verifying and ready states are observable in order`() = runBlocking {
        val release = release()
        val observed = mutableListOf<String>()
        val stager = FakeStager(tempDir.resolve("installer.exe")) { _, progress, verifying ->
            progress(5, 10)
            observed += "${(managerState.value as UpdateState.Downloading).bytesReceived}"
            verifying()
            observed += "verifying"
        }
        val manager = UpdateManager(FakeProvider(ReleaseCheckResult.UpdateAvailable(release, null)), stager)
        managerState = manager.state
        try {
            manager.check(false).join()
            manager.download().join()

            assertEquals(listOf("5", "verifying"), observed)
            val ready = assertIs<UpdateState.ReadyToInstall>(manager.state.value)
            assertEquals(tempDir.resolve("installer.exe"), ready.path)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `cancelling a download returns to available and can be retried`(): Unit = runBlocking {
        val release = release()
        val entered = CompletableDeferred<Unit>()
        val stager = object : InstallerPackageStager {
            override suspend fun stage(
                asset: InstallerAsset,
                onProgress: (Long, Long) -> Unit,
                onVerifying: () -> Unit,
            ): Path {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val manager = UpdateManager(FakeProvider(ReleaseCheckResult.UpdateAvailable(release, null)), stager)
        try {
            manager.check(false).join()
            val job = manager.download()
            entered.await()
            manager.cancelDownload()
            job.join()

            assertIs<UpdateState.Available>(manager.state.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `retry after verification failure redownloads the same approved release`(): Unit = runBlocking {
        val release = release()
        val attempts = AtomicInteger()
        val stager = object : InstallerPackageStager {
            override suspend fun stage(asset: InstallerAsset, onProgress: (Long, Long) -> Unit, onVerifying: () -> Unit): Path {
                if (attempts.incrementAndGet() == 1) throw InstallerStagingException(UpdateFailureCategory.DIGEST_MISMATCH)
                onProgress(asset.size, asset.size)
                onVerifying()
                return tempDir.resolve("installer.exe")
            }
        }
        val manager = UpdateManager(FakeProvider(ReleaseCheckResult.UpdateAvailable(release, null)), stager)
        try {
            manager.check(false).join()
            manager.download().join()
            assertEquals(UpdateFailureCategory.DIGEST_MISMATCH, assertIs<UpdateState.Failed>(manager.state.value).category)

            manager.retry().join()

            assertEquals(2, attempts.get())
            assertIs<UpdateState.ReadyToInstall>(manager.state.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `rate limit retry time prevents rapid automatic checks`() = runBlocking {
        val now = System.currentTimeMillis()
        val provider = FakeProvider(ReleaseCheckResult.Failed(ReleaseCheckFailure.RATE_LIMITED, now + 120_000))
        val manager = UpdateManager(provider, FakeStager(tempDir.resolve("installer.exe")), nowEpochMillis = { now })
        try {
            manager.check(false).join()
            manager.check(false).join()

            assertEquals(1, provider.calls.get())
            assertEquals(UpdateFailureCategory.RATE_LIMITED, assertIs<UpdateState.Failed>(manager.state.value).category)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `not modified keeps a cached available release after restart`() = runBlocking {
        val release = release()
        val provider = FakeProvider(ReleaseCheckResult.NotModified("etag-1"))
        val manager = UpdateManager(
            provider,
            FakeStager(tempDir.resolve("installer.exe")),
            initialEtag = "etag-1",
            initialRelease = release,
        )
        try {
            manager.check(false).join()

            assertEquals(release, assertIs<UpdateState.Available>(manager.state.value).release)
            assertEquals("etag-1", provider.lastEtag)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `persisted rate limit also blocks a check after restart`() = runBlocking {
        val now = System.currentTimeMillis()
        val provider = FakeProvider(ReleaseCheckResult.UpToDate(null))
        val manager = UpdateManager(
            provider,
            FakeStager(tempDir.resolve("installer.exe")),
            initialRetryAfterEpochMillis = now + 60_000L,
            nowEpochMillis = { now },
        )
        try {
            manager.check(manual = true).join()

            assertEquals(0, provider.calls.get())
            assertEquals(UpdateFailureCategory.RATE_LIMITED, assertIs<UpdateState.Failed>(manager.state.value).category)
        } finally {
            manager.close()
        }
    }

    private lateinit var managerState: kotlinx.coroutines.flow.StateFlow<UpdateState>

    private fun release() = UpdateRelease(
        version = requireNotNull(SemVer.parse("1.3.0")),
        tagName = "v1.3.0",
        prerelease = true,
        notes = "notes",
        asset = InstallerAsset(
            name = "DesktopMusicCandidate-1.3.0-Setup.exe",
            url = "https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
            size = 10,
            sha256 = "a".repeat(64),
        ),
    )

    private class FakeProvider(private val result: ReleaseCheckResult) : ReleaseProvider {
        val calls = AtomicInteger()
        var lastEtag: String? = null
        override suspend fun check(etag: String?): ReleaseCheckResult {
            calls.incrementAndGet()
            lastEtag = etag
            return result
        }
    }

    private class FakeStager(
        private val path: Path,
        private val block: (suspend (InstallerAsset, (Long, Long) -> Unit, () -> Unit) -> Unit)? = null,
    ) : InstallerPackageStager {
        override suspend fun stage(asset: InstallerAsset, onProgress: (Long, Long) -> Unit, onVerifying: () -> Unit): Path {
            block?.invoke(asset, onProgress, onVerifying) ?: run {
                onProgress(asset.size, asset.size)
                onVerifying()
            }
            return path
        }
    }
}
