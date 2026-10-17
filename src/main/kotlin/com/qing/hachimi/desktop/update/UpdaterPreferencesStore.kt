package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.DesktopProductIdentity
import com.qing.hachimi.desktop.SemVer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal data class UpdaterPreferences(
    val autoDownloadPreview: Boolean = true,
    val lastCheckEpochMillis: Long = 0L,
    val etag: String? = null,
    val pendingVersion: String? = null,
    val cachedRelease: UpdateRelease? = null,
    val retryAfterEpochMillis: Long? = null,
    val rateLimitKind: UpdateRateLimitKind? = null,
    val checkMirrorTemplate: String? = null,
    val installerMirrorTemplate: String? = null,
)

/** Small independent per-user file; update metadata never shares the music task JSON. */
internal class UpdaterPreferencesStore(private val file: Path) {
    fun load(): UpdaterPreferences = runCatching {
        if (!Files.isRegularFile(file)) return UpdaterPreferences()
        val json = JSONObject(Files.readString(file))
        val etag = json.optString("etag").takeIf { it.isNotBlank() && it.length <= MAX_ETAG_CHARS && isSafe(it) }
        val pending = json.optString("pendingVersion").takeIf { it.isNotBlank() && it.length <= MAX_VERSION_CHARS }
        val cachedRelease = json.optJSONObject("cachedRelease")?.let(::parseCachedRelease)
        UpdaterPreferences(
            autoDownloadPreview = json.optBoolean("autoDownloadPreview", true),
            lastCheckEpochMillis = json.optLong("lastCheckEpochMillis", 0L).coerceAtLeast(0L),
            etag = etag,
            pendingVersion = pending,
            cachedRelease = cachedRelease,
            retryAfterEpochMillis = json.optLong("retryAfterEpochMillis", -1L).takeIf { it >= 0L },
            rateLimitKind = json.optString("rateLimitKind")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { UpdateRateLimitKind.valueOf(it) }.getOrNull() },
            checkMirrorTemplate = normalizeMirrorTemplate(json.optString("checkMirrorTemplate")),
            installerMirrorTemplate = normalizeMirrorTemplate(json.optString("installerMirrorTemplate")),
        )
    }.getOrDefault(UpdaterPreferences())

    fun save(preferences: UpdaterPreferences) {
        val normalized = preferences.copy(
            lastCheckEpochMillis = preferences.lastCheckEpochMillis.coerceAtLeast(0L),
            retryAfterEpochMillis = preferences.retryAfterEpochMillis?.coerceAtLeast(0L),
            etag = preferences.etag?.takeIf { it.length <= MAX_ETAG_CHARS && isSafe(it) },
            pendingVersion = preferences.pendingVersion?.takeIf { it.length <= MAX_VERSION_CHARS },
            cachedRelease = preferences.cachedRelease?.takeIf { parseCachedRelease(it.toJson()) != null },
            checkMirrorTemplate = normalizeMirrorTemplate(preferences.checkMirrorTemplate),
            installerMirrorTemplate = normalizeMirrorTemplate(preferences.installerMirrorTemplate),
        )
        val absolute = file.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent)
        val temporary = parent.resolve("${absolute.fileName}.tmp")
        Files.createDirectories(parent)
        val json = JSONObject()
            .put("autoDownloadPreview", normalized.autoDownloadPreview)
            .put("lastCheckEpochMillis", normalized.lastCheckEpochMillis)
            .put("etag", normalized.etag)
            .put("pendingVersion", normalized.pendingVersion)
            .put("retryAfterEpochMillis", normalized.retryAfterEpochMillis)
            .put("rateLimitKind", normalized.rateLimitKind?.name)
            .put("cachedRelease", normalized.cachedRelease?.toJson())
            .put("checkMirrorTemplate", normalized.checkMirrorTemplate)
            .put("installerMirrorTemplate", normalized.installerMirrorTemplate)
        try {
            Files.writeString(
                temporary,
                json.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.io.IOException) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isSafe(value: String): Boolean = value.none { it == '\r' || it == '\n' || it.code < 0x20 }

    private fun normalizeMirrorTemplate(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_MIRROR_TEMPLATE_CHARS && isSafe(it) }
        ?.let { UpdateMirrorTemplate.parse(it)?.value }

    private fun parseCachedRelease(json: JSONObject): UpdateRelease? = runCatching {
        val version = SemVer.parse(json.getString("version")) ?: return null
        val tag = json.getString("tagName")
        if (tag.removePrefix("v") != version.toString() || tag !in setOf(version.toString(), "v$version")) return null
        val name = json.getString("assetName")
        if (name != "${DesktopProductIdentity.INSTALLER_NAME}-$version-Setup.exe") return null
        val url = json.getString("assetUrl").toHttpUrlOrNull() ?: return null
        val expectedPath = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/$tag/$name"
        if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.querySize != 0 ||
            url.fragment != null || url.encodedPath != expectedPath
        ) return null
        val size = json.optLong("assetSize", -1L)
        val digest = json.optString("assetSha256")
        if (size !in 1..MAX_PACKAGE_BYTES || !SHA256_PATTERN.matches(digest)) return null
        UpdateRelease(
            version = version,
            tagName = tag,
            prerelease = json.optBoolean("prerelease", false),
            notes = json.optString("notes").take(MAX_NOTES_CHARS),
            asset = InstallerAsset(name, url, size, digest.lowercase(), tag),
        )
    }.getOrNull()

    private fun UpdateRelease.toJson() = JSONObject()
        .put("version", version.toString())
        .put("tagName", tagName)
        .put("prerelease", prerelease)
        .put("notes", notes.take(MAX_NOTES_CHARS))
        .put("assetName", asset.name)
        .put("assetUrl", asset.url.toString())
        .put("assetSize", asset.size)
        .put("assetSha256", asset.sha256)

    private companion object {
        const val MAX_ETAG_CHARS = 512
        const val MAX_VERSION_CHARS = 128
        const val MAX_MIRROR_TEMPLATE_CHARS = 2_048
        const val MAX_NOTES_CHARS = 20_000
        const val MAX_PACKAGE_BYTES = 1_500_000_000L
        const val REPOSITORY_OWNER = "ChangfengluoO71"
        const val REPOSITORY_NAME = "desktop-music-candidate"
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
