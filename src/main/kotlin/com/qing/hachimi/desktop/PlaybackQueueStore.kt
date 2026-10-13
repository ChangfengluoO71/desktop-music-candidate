package com.qing.hachimi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

data class RestoredPlaybackQueue(
    val queue: List<LocalTrack> = emptyList(),
    val queueIndex: Int = -1,
    val positionMillis: Long = 0,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val missingFiles: List<String> = emptyList(),
    val warning: String? = null,
    val autoPlay: Boolean = false,
)

/** Atomic, local-only persistence for the playback queue; it contains no service/session data. */
class PlaybackQueueStore(private val file: Path) {
    fun save(state: PlaybackState) {
        val parent = file.toAbsolutePath().normalize().parent
        Files.createDirectories(parent)
        val queue = JSONArray()
        state.queue.forEach { track ->
            queue.put(
                JSONObject()
                    .put("pathKey", track.pathKey)
                    .put("filePath", track.filePath)
                    .put("title", track.title),
            )
        }
        val selected = state.currentTrack ?: state.queue.getOrNull(state.queueIndex)
        val json = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("queue", queue)
            .put("currentPathKey", selected?.pathKey)
            .put("positionMillis", state.positionMillis.coerceAtLeast(0))
            .put("volume", state.volume.coerceIn(0f, 1f).toDouble())
            .put("shuffle", state.shuffle)
            .put("repeatMode", state.repeatMode.name)
            .toString(2)
        val temp = Files.createTempFile(parent, "${file.fileName}.", ".tmp")
        try {
            Files.writeString(temp, json, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun load(availableTracks: List<LocalTrack>): RestoredPlaybackQueue {
        if (!Files.exists(file)) return RestoredPlaybackQueue()
        return runCatching {
            val json = JSONObject(Files.readString(file))
            require(json.optInt("version") == SCHEMA_VERSION) { "Unsupported playback queue schema" }
            val available = availableTracks.associateBy { normalize(it.pathKey) }
            val queueJson = json.optJSONArray("queue") ?: JSONArray()
            val queue = mutableListOf<LocalTrack>()
            val missing = mutableListOf<String>()
            for (index in 0 until queueJson.length()) {
                val row = queueJson.optJSONObject(index) ?: continue
                val key = row.optString("pathKey")
                val resolved = available[normalize(key)]
                if (resolved == null) missing += row.optString("filePath").ifBlank { row.optString("title") }
                else if (queue.none { it.pathKey == resolved.pathKey }) queue += resolved
            }
            val currentKey = json.optString("currentPathKey").takeUnless { it == "null" }.orEmpty()
            val currentIndex = queue.indexOfFirst { normalize(it.pathKey) == normalize(currentKey) }
            val volume = json.optDouble("volume", 1.0).takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f) ?: 1f
            RestoredPlaybackQueue(
                queue = queue,
                queueIndex = currentIndex,
                positionMillis = if (currentIndex >= 0) json.optLong("positionMillis").coerceAtLeast(0) else 0,
                volume = volume,
                shuffle = json.optBoolean("shuffle"),
                repeatMode = json.optString("repeatMode").let { value ->
                    runCatching { PlaybackRepeatMode.valueOf(value) }.getOrDefault(PlaybackRepeatMode.OFF)
                },
                missingFiles = missing,
                warning = if (missing.isNotEmpty()) "队列中有 ${missing.size} 个文件当前不可用，已跳过；没有自动开始播放。" else null,
            )
        }.getOrElse {
            RestoredPlaybackQueue(warning = "播放队列记录损坏，已安全忽略；原始音乐文件未修改。")
        }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').lowercase(Locale.ROOT)

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
