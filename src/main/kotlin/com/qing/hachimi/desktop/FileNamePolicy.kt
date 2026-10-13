package com.qing.hachimi.desktop

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Path-only utilities: no external services or Android dependencies. */
object FileNamePolicy {
    private const val MAX_DESTINATION_PATH_CHARS = 240
    private val bannedWindows = Regex("[<>:\"/\\\\|?*\\p{Cntrl}]")
    private val reserved = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

    fun component(raw: String): String {
        val cleaned = raw.replace(bannedWindows, "_").trim().trimEnd('.', ' ').take(90).trimEnd('.', ' ')
        return when {
            cleaned.isBlank() -> "unknown"
            reserved.matches(cleaned) -> "_$cleaned"
            else -> cleaned
        }
    }

    fun extension(type: String?): String = when (type?.lowercase()) {
        "mp3" -> ".mp3"
        "flac" -> ".flac"
        "m4a", "mp4" -> ".m4a"
        "aac" -> ".aac"
        "wav" -> ".wav"
        "ogg" -> ".ogg"
        else -> ".audio"
    }

    fun imageExtension(mimeType: String): String? = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> null
    }

    fun destination(
        folder: Path,
        song: SongHit,
        type: String?,
        reservedPaths: Set<Path> = emptySet(),
        conflictPolicy: FileConflictPolicy = FileConflictPolicy.KEEP_BOTH,
    ): Path {
        val directory = folder.toAbsolutePath().normalize()
        val visibleName = "${component(song.artists)} - ${component(song.name)}"
        val ext = extension(type)
        fun candidate(index: Int): Path {
            val collisionSuffix = if (index == 1) "" else " ($index)"
            val fixedSuffix = " [${song.id}]$collisionSuffix$ext"
            val available = MAX_DESTINATION_PATH_CHARS - directory.toString().length - 1 - fixedSuffix.length
            require(available > 0) { "下载目录太长，无法在安全路径长度内创建文件名" }
            val prefix = truncateUtf16(visibleName, available).trimEnd('.', ' ')
            require(prefix.isNotBlank()) { "下载目录太长，无法在安全路径长度内保留歌曲标识" }
            return directory.resolve(prefix + fixedSuffix).normalize()
        }
        val base = candidate(1)
        if (conflictPolicy == FileConflictPolicy.FAIL_IF_EXISTS) return base
        var target = base
        var index = 2
        while (Files.exists(target) || target in reservedPaths) {
            target = candidate(index)
            index++
        }
        return target
    }

    private fun truncateUtf16(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        var end = maxLength
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }

    fun withExtension(path: Path, type: String?): Path {
        val name = path.fileName.toString()
        val extensionStart = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = extensionStart?.let { name.substring(0, it) } ?: name
        return path.resolveSibling(stem + extension(type))
    }

    /** Creates a grouped directory using sanitized components and rejects link-based escapes. */
    fun groupedDirectory(
        root: Path,
        grouping: FolderGrouping,
        collectionName: String,
        song: SongHit,
    ): Path {
        val absoluteRoot = root.toAbsolutePath().normalize()
        Files.createDirectories(absoluteRoot)
        val realRoot = absoluteRoot.toRealPath()
        val components = when (grouping) {
            FolderGrouping.NONE -> emptyList()
            FolderGrouping.ARTIST_ALBUM -> listOf("${song.artists.ifBlank { "Unknown Artist" }} - ${song.album.ifBlank { "Unknown Album" }}")
            FolderGrouping.PLAYLIST -> listOf("Playlists", collectionName.ifBlank { "Unnamed Collection" })
        }.map(::component)

        var current = realRoot
        for (part in components) {
            val next = current.resolve(part).normalize()
            require(next.startsWith(realRoot)) { "分组目录超出下载根目录" }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Validate the concurrently-created entry below.
                }
            }
            require(Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) { "分组路径不是目录" }
            require(!Files.isSymbolicLink(next)) { "分组目录不能是符号链接" }
            val realNext = next.toRealPath()
            require(realNext.startsWith(realRoot)) { "分组目录链接超出下载根目录" }
            current = realNext
        }
        return current
    }
}
