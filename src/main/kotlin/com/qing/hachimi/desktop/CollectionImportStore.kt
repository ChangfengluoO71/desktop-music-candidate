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

data class CollectionImportItem(
    val originalIndex: Int,
    val songId: Long?,
    val songName: String,
    val artists: String,
    val album: String,
    val status: CollectionItemStatus,
    val downloadTaskId: String? = null,
    val message: String? = null,
) {
    init { require(originalIndex > 0); require(songId == null || songId > 0L) }
}

data class CollectionImport(
    val id: String,
    val sourceType: CollectionSourceType,
    val sourceId: Long,
    val sourceName: String,
    val requestedQuality: String,
    val downloadDirectory: String,
    val createdAtMillis: Long,
    val folderGrouping: FolderGrouping = FolderGrouping.NONE,
    val coverUrl: String = "",
    val items: List<CollectionImportItem>,
) {
    init {
        require(id.isNotBlank())
        require(sourceId > 0L)
        require(requestedQuality in setOf("standard", "exhigh", "lossless", "hires"))
        require(downloadDirectory.isNotBlank())
    }

    val totalCount: Int get() = items.size
    val resolvedCount: Int get() = items.count {
        it.songId != null && it.status in setOf(
            CollectionItemStatus.RESOLVED,
            CollectionItemStatus.PREPARING,
            CollectionItemStatus.QUEUED,
        )
    }
    val failedCount: Int get() = items.count {
        it.status in setOf(CollectionItemStatus.FAILED, CollectionItemStatus.UNAUTHORIZED)
    }
    val skippedCount: Int get() = items.count {
        it.status in setOf(CollectionItemStatus.MISSING, CollectionItemStatus.SKIPPED)
    }
}

/** Atomic, credential-free persistence for collection membership and import results. */
class CollectionImportStore(private val file: Path) {
    fun load(): List<CollectionImport> {
        if (!Files.exists(file)) return emptyList()
        val root = runCatching { JSONObject(Files.readString(file, StandardCharsets.UTF_8)) }
            .getOrElse {
                preserveCorruptFile()
                return emptyList()
            }
        val rows = root.optJSONArray("imports") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                runCatching { rows.getJSONObject(index).toCollectionImport() }.getOrNull()?.let(::add)
            }
        }
    }

    fun save(imports: List<CollectionImport>) {
        val document = JSONObject()
            .put("schemaVersion", 1)
            .put("imports", JSONArray().apply { imports.forEach { put(it.toJson()) } })
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
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun preserveCorruptFile() {
        runCatching {
            Files.move(file, file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}"))
        }
    }

    private fun CollectionImport.toJson() = JSONObject()
        .put("id", id)
        .put("sourceType", sourceType.name)
        .put("sourceId", sourceId)
        .put("sourceName", sourceName)
        .put("requestedQuality", requestedQuality)
        .put("downloadDirectory", downloadDirectory)
        .put("createdAtMillis", createdAtMillis)
        .put("folderGrouping", folderGrouping.name)
        .put("coverUrl", coverUrl)
        .put("items", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("originalIndex", item.originalIndex)
                    .put("songId", item.songId)
                    .put("songName", item.songName)
                    .put("artists", item.artists)
                    .put("album", item.album)
                    .put("status", item.status.name)
                    .put("downloadTaskId", item.downloadTaskId)
                    .put("message", item.message?.take(300)))
            }
        })

    private fun JSONObject.toCollectionImport(): CollectionImport {
        val itemRows = optJSONArray("items") ?: JSONArray()
        val items = (0 until itemRows.length()).map { index ->
            val row = itemRows.getJSONObject(index)
            CollectionImportItem(
                originalIndex = row.getInt("originalIndex"),
                songId = if (row.isNull("songId")) null else row.optLong("songId").takeIf { it > 0L },
                songName = row.optString("songName"),
                artists = row.optString("artists"),
                album = row.optString("album"),
                status = CollectionItemStatus.valueOf(row.getString("status")),
                downloadTaskId = row.optNullableString("downloadTaskId"),
                message = row.optNullableString("message"),
            )
        }
        return CollectionImport(
            id = getString("id"),
            sourceType = CollectionSourceType.valueOf(getString("sourceType")),
            sourceId = getLong("sourceId"),
            sourceName = optString("sourceName"),
            requestedQuality = getString("requestedQuality"),
            downloadDirectory = getString("downloadDirectory"),
            createdAtMillis = getLong("createdAtMillis"),
            folderGrouping = runCatching {
                FolderGrouping.valueOf(optString("folderGrouping", FolderGrouping.NONE.name))
            }.getOrDefault(FolderGrouping.NONE),
            coverUrl = optString("coverUrl"),
            items = items,
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}
