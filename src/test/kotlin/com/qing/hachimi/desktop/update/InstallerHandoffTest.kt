package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.SemVer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallerHandoffTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `only the verified package is handed off after pending version and orderly shutdown`() = runBlocking {
        val setup = stagedPackage()
        val preferences = UpdaterPreferencesStore(root.resolve("updates.json"))
        val events = mutableListOf<String>()
        val handoff = InstallerHandoff(
            stagingDirectory = setup.parent,
            preferencesStore = preferences,
            shutdown = {
                assertEquals("1.3.0", preferences.load().pendingVersion)
                events += "shutdown"
            },
            launchInstaller = { path ->
                assertEquals(setup, path)
                events += "launch"
            },
        )

        val result = handoff.start(setup, release())

        assertIs<HandoffResult.Started>(result)
        assertEquals(listOf("shutdown", "launch"), events)
    }

    @Test
    fun `modified or arbitrary package is rejected without shutdown or launch`() = runBlocking {
        val setup = stagedPackage().also {
            val changed = Files.readAllBytes(it)
            changed[0] = (changed[0].toInt() xor 0x01).toByte()
            Files.write(it, changed)
        }
        val preferences = UpdaterPreferencesStore(root.resolve("updates.json"))
        var shutdown = false
        var launched = false
        val handoff = InstallerHandoff(
            stagingDirectory = setup.parent,
            preferencesStore = preferences,
            shutdown = { shutdown = true },
            launchInstaller = { launched = true },
        )

        val result = handoff.start(setup, release())

        assertEquals(UpdateFailureCategory.DIGEST_MISMATCH, assertIs<HandoffResult.Failed>(result).category)
        assertTrue(!shutdown && !launched)
        assertEquals(null, preferences.load().pendingVersion)
    }

    @Test
    fun `installer launch error clears pending version but reports safe failure`() = runBlocking {
        val setup = stagedPackage()
        val preferences = UpdaterPreferencesStore(root.resolve("updates.json"))
        val events = mutableListOf<String>()
        val handoff = InstallerHandoff(
            stagingDirectory = setup.parent,
            preferencesStore = preferences,
            shutdown = { events += "shutdown" },
            launchInstaller = { events += "launch"; throw SecurityException("denied") },
        )

        val result = handoff.start(setup, release())

        assertEquals(UpdateFailureCategory.INSTALLER_LAUNCH_DENIED, assertIs<HandoffResult.Failed>(result).category)
        assertEquals(listOf("shutdown", "launch"), events)
        assertEquals(null, preferences.load().pendingVersion)
    }

    @Test
    fun `next startup clears pending only when the expected version is running`() {
        val setup = stagedPackage()
        val preferences = UpdaterPreferencesStore(root.resolve("updates.json"))
        preferences.save(UpdaterPreferences(pendingVersion = "1.3.0"))
        val handoff = InstallerHandoff(setup.parent, preferences, shutdown = {}, launchInstaller = {})

        assertEquals(PendingVersionStatus.MISMATCH, handoff.reconcileRunningVersion("1.2.0"))
        assertEquals("1.3.0", preferences.load().pendingVersion)
        assertEquals(PendingVersionStatus.CONFIRMED, handoff.reconcileRunningVersion("1.3.0"))
        assertEquals(null, preferences.load().pendingVersion)
    }

    @Test
    fun `Inno keeps its application identity and custom install directory page`() {
        val setup = Files.readString(Path.of("installer", "setup.iss"))

        assertTrue(setup.contains("AppId={{E1B24D8D-F6F8-429B-9F56-7D0C37C8F2B5}"))
        assertTrue(setup.contains("DisableDirPage=no"))
        assertTrue(setup.contains("CloseApplications=yes"))
        assertTrue(setup.contains("RestartApplications=no"))
        assertTrue(setup.contains("Flags: nowait postinstall skipifsilent"))
    }

    private fun stagedPackage(): Path {
        val directory = root.resolve("updates")
        Files.createDirectories(directory)
        return directory.resolve("DesktopMusicCandidate-1.3.0-Setup.exe").also {
            Files.write(it, PAYLOAD)
        }
    }

    private fun release() = UpdateRelease(
        version = requireNotNull(SemVer.parse("1.3.0")),
        tagName = "v1.3.0",
        prerelease = true,
        notes = "",
        asset = InstallerAsset(
            "DesktopMusicCandidate-1.3.0-Setup.exe",
            "https://github.com/ChangfengluO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
            PAYLOAD.size.toLong(),
            sha256(PAYLOAD),
        ),
    )

    private companion object {
        val PAYLOAD = "verified installer payload".encodeToByteArray()
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
