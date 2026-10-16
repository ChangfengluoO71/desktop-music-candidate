package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.SemVer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdaterPreferencesStoreTest {
    @Test
    fun `preferences retain automatic preview choice check time and etag`() {
        val root = Files.createTempDirectory("dmc-updater-preferences")
        val store = UpdaterPreferencesStore(root.resolve("updates.json"))
        val expected = UpdaterPreferences(
            autoDownloadPreview = false,
            lastCheckEpochMillis = 1_797_000_000_000,
            etag = "\"release-7\"",
            cachedRelease = release(),
            retryAfterEpochMillis = 1_797_000_120_000,
        )

        store.save(expected)

        assertEquals(expected, store.load())
        assertTrue(Files.readString(root.resolve("updates.json")).contains("release-7"))
        assertTrue(!Files.readString(root.resolve("updates.json")).contains("?"))
    }

    @Test
    fun `missing or damaged preference data uses safe defaults`() {
        val root = Files.createTempDirectory("dmc-updater-preferences-damaged")
        val file = root.resolve("updates.json")
        val store = UpdaterPreferencesStore(file)

        assertEquals(UpdaterPreferences(), store.load())
        Files.writeString(file, "{broken")
        assertEquals(UpdaterPreferences(), store.load())
    }

    @Test
    fun `cached release rejects signed or untrusted asset urls`() {
        val root = Files.createTempDirectory("dmc-updater-preferences-url")
        val file = root.resolve("updates.json")
        val store = UpdaterPreferencesStore(file)
        store.save(UpdaterPreferences(cachedRelease = release()))
        val text = Files.readString(file).replace("github.com/ChangfengluO71", "evil.invalid/ChangfengluO71")
        Files.writeString(file, text)

        assertEquals(null, store.load().cachedRelease)
    }

    private fun release() = UpdateRelease(
        version = requireNotNull(SemVer.parse("1.3.0")),
        tagName = "v1.3.0",
        prerelease = true,
        notes = "preview release notes",
        asset = InstallerAsset(
            "DesktopMusicCandidate-1.3.0-Setup.exe",
            "https://github.com/ChangfengluO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
            120,
            "a".repeat(64),
            "v1.3.0",
        ),
    )
}
