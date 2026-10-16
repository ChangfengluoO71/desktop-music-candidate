package com.qing.hachimi.desktop.update

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal sealed interface HandoffResult {
    data object Started : HandoffResult
    data class Failed(val category: UpdateFailureCategory) : HandoffResult
}

internal enum class PendingVersionStatus { NONE, CONFIRMED, MISMATCH }

/** Validates the staged package again, persists the target version, then performs an explicit Inno handoff. */
internal class InstallerHandoff(
    private val stagingDirectory: Path,
    private val preferencesStore: UpdaterPreferencesStore,
    private val shutdown: suspend () -> Unit,
    private val launchInstaller: (Path) -> Unit = { path ->
        ProcessBuilder(path.toAbsolutePath().normalize().toString()).directory(path.toAbsolutePath().parent.toFile()).start()
    },
) {
    suspend fun start(path: Path, release: UpdateRelease): HandoffResult {
        val packageFailure = verifyStagedPackage(path, release)
        if (packageFailure != null) return HandoffResult.Failed(packageFailure)
        val verifiedPath = path.toAbsolutePath().normalize()

        val previous = preferencesStore.load()
        try {
            preferencesStore.save(previous.copy(pendingVersion = release.version.toString()))
        } catch (_: Throwable) {
            return HandoffResult.Failed(UpdateFailureCategory.WRITE_FAILED)
        }

        try {
            shutdown()
        } catch (cancelled: CancellationException) {
            clearPending(previous)
            throw cancelled
        } catch (_: Throwable) {
            clearPending(previous)
            return HandoffResult.Failed(UpdateFailureCategory.INSTALLER_FAILED)
        }

        return try {
            launchInstaller(verifiedPath)
            HandoffResult.Started
        } catch (_: SecurityException) {
            clearPending(previous)
            HandoffResult.Failed(UpdateFailureCategory.INSTALLER_LAUNCH_DENIED)
        } catch (_: IOException) {
            clearPending(previous)
            HandoffResult.Failed(UpdateFailureCategory.INSTALLER_LAUNCH_DENIED)
        } catch (_: IllegalArgumentException) {
            clearPending(previous)
            HandoffResult.Failed(UpdateFailureCategory.INSTALLER_LAUNCH_DENIED)
        } catch (_: Throwable) {
            clearPending(previous)
            HandoffResult.Failed(UpdateFailureCategory.INSTALLER_LAUNCH_DENIED)
        }
    }

    fun reconcileRunningVersion(currentVersion: String): PendingVersionStatus {
        val preferences = preferencesStore.load()
        val expected = preferences.pendingVersion ?: return PendingVersionStatus.NONE
        if (expected != currentVersion) return PendingVersionStatus.MISMATCH
        val installed = com.qing.hachimi.desktop.SemVer.parse(currentVersion)
        preferencesStore.save(
            preferences.copy(
                pendingVersion = null,
                cachedRelease = preferences.cachedRelease?.takeIf { cached -> installed?.let { cached.version > it } == true },
            ),
        )
        return PendingVersionStatus.CONFIRMED
    }

    private fun verifyStagedPackage(path: Path, release: UpdateRelease): UpdateFailureCategory? = try {
        val directory = stagingDirectory.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        val expected = directory.resolve(release.asset.name).normalize()
        if (candidate != expected || !candidate.startsWith(directory) ||
            Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
        ) return UpdateFailureCategory.INVALID_METADATA
        if (Files.size(candidate) != release.asset.size) return UpdateFailureCategory.SIZE_MISMATCH
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(candidate).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual.equals(release.asset.sha256, ignoreCase = true)) null else UpdateFailureCategory.DIGEST_MISMATCH
    } catch (_: Throwable) {
        UpdateFailureCategory.WRITE_FAILED
    }

    private fun clearPending(previous: UpdaterPreferences) {
        runCatching { preferencesStore.save(previous) }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
