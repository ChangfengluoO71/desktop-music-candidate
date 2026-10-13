package com.qing.hachimi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

data class DownloadTaskStoreSnapshot(
    val tasks: List<DownloadTask>,
    val settings: DesktopSettings,
)

/** Small, versioned JSON store. Writes are replaced atomically and records are loaded independently. */
class DownloadTaskStore(private val file: Path) {
    fun load(): DownloadTaskStoreSnapshot {
        if (!Files.exists(file)) return DownloadTaskStoreSnapshot(emptyList(), DesktopSettings())
        val root = try {
            JSONObject(Files.readString(file, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            preserveCorruptFile()
            return DownloadTaskStoreSnapshot(emptyList(), DesktopSettings())
        }

        val tasks = buildList {
            val rows = root.optJSONArray("tasks") ?: JSONArray()
            for (index in 0 until rows.length()) {
                runCatching { rows.getJSONObject(index).toTask() }
                    .onSuccess(::add)
            }
        }
        val settings = runCatching { root.optJSONObject("settings")?.toSettings() }
            .getOrNull() ?: DesktopSettings()
        return DownloadTaskStoreSnapshot(tasks, settings)
    }

    fun save(tasks: List<DownloadTask>, settings: DesktopSettings) {
        val document = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("settings", settings.toJson())
            .put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
        try {
            val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun preserveCorruptFile() {
        runCatching {
            val backup = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
            Files.move(file, backup)
        }
    }

    private fun DownloadTask.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("songId", songId)
        .put("songName", songName)
        .put("artists", artists)
        .put("album", album)
        .put("requestedQuality", requestedQuality)
        .put("actualQuality", actualQuality)
        .put("filePath", filePath)
        .put("bytesDownloaded", bytesDownloaded)
        .put("totalBytes", totalBytes)
        .put("speedBytesPerSecond", speedBytesPerSecond)
        .put("status", status.name)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
        .put("errorType", errorType?.name)
        .put("errorSummary", sanitizeSummary(errorSummary))
        .put("automaticRetriesUsed", automaticRetriesUsed)
        .put("etag", etag)
        .put("lastModified", lastModified)
        .put("collectionImportIds", JSONArray(collectionImportIds))
        .put("coverUrl", coverUrl)
        .put("trackNumber", trackNumber)
        .put("trackTotal", trackTotal)
        .put("discNumber", discNumber)
        .put("discTotal", discTotal)
        .put("releaseYear", releaseYear)
        .put("albumArtist", albumArtist)
        .put("aliases", JSONArray(aliases))
        .put("translatedTitles", JSONArray(translatedTitles))
        .put("postProcessingOptions", postProcessingOptions.toJson())
        .put("postProcessingStatus", postProcessingStatus.name)
        .put("postProcessingMessage", sanitizeSummary(postProcessingMessage))

    private fun JSONObject.toTask(): DownloadTask = DownloadTask(
        id = getString("id"),
        songId = getLong("songId"),
        songName = getString("songName"),
        artists = optString("artists"),
        album = optString("album"),
        requestedQuality = getString("requestedQuality"),
        actualQuality = optNullableString("actualQuality"),
        filePath = getString("filePath"),
        bytesDownloaded = optLong("bytesDownloaded"),
        totalBytes = if (isNull("totalBytes")) null else optLong("totalBytes"),
        speedBytesPerSecond = optLong("speedBytesPerSecond"),
        status = DownloadTaskStatus.valueOf(getString("status")),
        createdAtMillis = getLong("createdAtMillis"),
        updatedAtMillis = getLong("updatedAtMillis"),
        errorType = optNullableString("errorType")?.let(DownloadErrorType::valueOf),
        errorSummary = sanitizeSummary(optNullableString("errorSummary")),
        automaticRetriesUsed = optInt("automaticRetriesUsed", 0).coerceIn(0, MAX_AUTOMATIC_DOWNLOAD_RETRIES),
        etag = optNullableString("etag"),
        lastModified = optNullableString("lastModified"),
        collectionImportIds = optStringList("collectionImportIds"),
        coverUrl = optString("coverUrl"),
        trackNumber = optNullableInt("trackNumber"),
        trackTotal = optNullableInt("trackTotal"),
        discNumber = optNullableInt("discNumber"),
        discTotal = optNullableInt("discTotal"),
        releaseYear = optNullableInt("releaseYear"),
        albumArtist = optString("albumArtist"),
        aliases = optStringList("aliases"),
        translatedTitles = optStringList("translatedTitles"),
        postProcessingOptions = optJSONObject("postProcessingOptions")?.toPostProcessingOptions()
            ?: AudioPostProcessingOptions(),
        postProcessingStatus = runCatching {
            PostProcessingStatus.valueOf(optString("postProcessingStatus", PostProcessingStatus.NOT_REQUESTED.name))
        }.getOrDefault(PostProcessingStatus.NOT_REQUESTED),
        postProcessingMessage = optNullableString("postProcessingMessage"),
    )

    private fun JSONObject.toSettings() = DesktopSettings(
        defaultDownloadDirectory = optString(
            "defaultDownloadDirectory",
            DesktopSettings().defaultDownloadDirectory,
        ),
        maxConcurrentDownloads = optInt("maxConcurrentDownloads", 2),
        defaultQuality = optString("defaultQuality", "exhigh"),
        fileConflictPolicy = runCatching {
            FileConflictPolicy.valueOf(optString("fileConflictPolicy", "KEEP_BOTH"))
        }.getOrDefault(FileConflictPolicy.KEEP_BOTH),
        folderGrouping = runCatching {
            FolderGrouping.valueOf(optString("folderGrouping", FolderGrouping.NONE.name))
        }.getOrDefault(FolderGrouping.NONE),
        downloadLyrics = optBoolean("downloadLyrics", false),
        saveTranslatedLyrics = optBoolean("saveTranslatedLyrics", false),
        saveRomanizedLyrics = optBoolean("saveRomanizedLyrics", false),
        saveWordLevelLyrics = optBoolean("saveWordLevelLyrics", false),
        embedLyrics = optBoolean("embedLyrics", false),
        writeAudioTags = optBoolean("writeAudioTags", false),
        embedCoverArt = optBoolean("embedCoverArt", false),
        coverDirectoryMode = runCatching {
            CoverDirectoryMode.valueOf(optString("coverDirectoryMode", CoverDirectoryMode.BESIDE_MEDIA.name))
        }.getOrDefault(CoverDirectoryMode.BESIDE_MEDIA),
        customCoverDirectory = optString("customCoverDirectory", ""),
        network = readNetworkSettings(),
    )

    private fun JSONObject.readNetworkSettings(): DesktopNetworkSettings {
        val network = optJSONObject("network") ?: return DesktopNetworkSettings()
        return runCatching {
            val mode = DesktopNetworkMode.valueOf(
                network.optString("mode", DesktopNetworkMode.DIRECT.name),
            )
            val proxyType = DesktopProxyType.valueOf(
                network.optString("proxyType", DesktopProxyType.HTTP.name),
            )
            val proxyHost = if (mode == DesktopNetworkMode.CUSTOM_PROXY) {
                network.opt("proxyHost") as? String ?: error("Custom proxy host is missing or invalid")
            } else {
                network.optString("proxyHost", "")
            }
            val proxyPort = if (mode == DesktopNetworkMode.CUSTOM_PROXY) {
                val portValue = network.opt("proxyPort") as? Number
                    ?: error("Custom proxy port is missing or invalid")
                val numericPort = portValue.toDouble()
                require(numericPort.isFinite() && numericPort % 1.0 == 0.0 && numericPort in 1.0..65_535.0) {
                    "Custom proxy port is missing or invalid"
                }
                numericPort.toInt()
            } else {
                network.optInt("proxyPort", 7890)
            }
            DesktopNetworkSettings(
                mode = mode,
                proxyType = proxyType,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
            ).also { settings ->
                require(settings.validationError() == null)
            }
        }.getOrDefault(DesktopNetworkSettings())
    }

    private fun DesktopSettings.toJson(): JSONObject {
        val safeProxyEndpoint = network.copy(mode = DesktopNetworkMode.CUSTOM_PROXY)
            .takeIf { it.validationError() == null }
        return JSONObject()
            .put("defaultDownloadDirectory", defaultDownloadDirectory)
            .put("maxConcurrentDownloads", maxConcurrentDownloads)
            .put("defaultQuality", defaultQuality)
            .put("fileConflictPolicy", fileConflictPolicy.name)
            .put("folderGrouping", folderGrouping.name)
            .put("downloadLyrics", downloadLyrics)
            .put("saveTranslatedLyrics", saveTranslatedLyrics)
            .put("saveRomanizedLyrics", saveRomanizedLyrics)
            .put("saveWordLevelLyrics", saveWordLevelLyrics)
            .put("embedLyrics", embedLyrics)
            .put("writeAudioTags", writeAudioTags)
            .put("embedCoverArt", embedCoverArt)
            .put("coverDirectoryMode", coverDirectoryMode.name)
            .put("customCoverDirectory", customCoverDirectory)
            .put("network", JSONObject()
                .put("mode", network.mode.name)
                .put("proxyType", network.proxyType.name)
                // Preserve a valid dormant endpoint for convenience, but never write arbitrary
                // text from an inactive host field (which could contain pasted credentials).
                .put("proxyHost", safeProxyEndpoint?.proxyHost ?: "")
                .put("proxyPort", safeProxyEndpoint?.proxyPort ?: 7890))
    }

    private fun AudioPostProcessingOptions.toJson() = JSONObject()
        .put("downloadLyrics", downloadLyrics)
        .put("saveTranslatedLyrics", saveTranslatedLyrics)
        .put("saveRomanizedLyrics", saveRomanizedLyrics)
        .put("saveWordLevelLyrics", saveWordLevelLyrics)
        .put("embedLyrics", embedLyrics)
        .put("writeAudioTags", writeAudioTags)
        .put("embedCoverArt", embedCoverArt)

    private fun JSONObject.toPostProcessingOptions() = AudioPostProcessingOptions(
        downloadLyrics = optBoolean("downloadLyrics", false),
        saveTranslatedLyrics = optBoolean("saveTranslatedLyrics", false),
        saveRomanizedLyrics = optBoolean("saveRomanizedLyrics", false),
        saveWordLevelLyrics = optBoolean("saveWordLevelLyrics", false),
        embedLyrics = optBoolean("embedLyrics", false),
        writeAudioTags = optBoolean("writeAudioTags", false),
        embedCoverArt = optBoolean("embedCoverArt", false),
    )

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key).takeIf { it > 0 }

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    companion object {
        private const val SCHEMA_VERSION = 3
        private val signedUrl = Regex("(?i)https?://[^\\s\\\"'<>]+")
        private val credential = Regex(
            "(?i)(MUSIC_U|MUSIC_A|COOKIE|AUTHORIZATION|TOKEN|SIGNATURE)(\\s*[:=]\\s*)[^\\s&,;]+",
        )

        internal fun sanitizeSummary(value: String?): String? = value
            ?.replace(signedUrl, "[URL 已隐藏]")
            ?.replace(credential, "[认证字段已隐藏]")
            ?.take(300)
            ?.takeIf(String::isNotBlank)
    }
}
