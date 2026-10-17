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
            rateLimitKind = UpdateRateLimitKind.PRIMARY_API_QUOTA,
            checkMirrorTemplate = "https://gh-proxy.com/{url}",
            installerMirrorTemplate = "https://ghfast.top/{url}",
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
    fun `custom update check and installer mirrors survive persistence`() {
        val root = Files.createTempDirectory("dmc-updater-mirrors")
        val file = root.resolve("updates.json")
        val store = UpdaterPreferencesStore(file)
        val checkMirror = "https://gh-proxy.com/{url}"
        val installerMirror = "https://ghfast.top/{url}"
        Files.writeString(
            file,
            org.json.JSONObject()
                .put("checkMirrorTemplate", checkMirror)
                .put("installerMirrorTemplate", installerMirror)
                .toString(),
        )

        store.save(store.load())

        val saved = org.json.JSONObject(Files.readString(file))
        assertEquals(checkMirror, saved.optString("checkMirrorTemplate"))
        assertEquals(installerMirror, saved.optString("installerMirrorTemplate"))
    }

    @Test
    fun `invalid mirror templates are discarded when preferences are loaded and saved`() {
        val root = Files.createTempDirectory("dmc-updater-mirrors-invalid")
        val file = root.resolve("updates.json")
        val store = UpdaterPreferencesStore(file)
        Files.writeString(
            file,
            org.json.JSONObject()
                .put("checkMirrorTemplate", "http://insecure.example/{url}")
                .put("installerMirrorTemplate", "https://user:password@mirror.example/{url}")
                .toString(),
        )

        store.save(store.load())

        val saved = org.json.JSONObject(Files.readString(file))
        assertTrue(saved.isNull("checkMirrorTemplate"))
        assertTrue(saved.isNull("installerMirrorTemplate"))
    }

    @Test
    fun `cached release rejects signed or untrusted asset urls`() {
        val root = Files.createTempDirectory("dmc-updater-preferences-url")
        val file = root.resolve("updates.json")
        val store = UpdaterPreferencesStore(file)
        store.save(UpdaterPreferences(cachedRelease = release()))
        val text = Files.readString(file).replace("github.com/ChangfengluoO71", "evil.invalid/ChangfengluoO71")
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
            "https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
            120,
            "a".repeat(64),
            "v1.3.0",
        ),
    )
}
