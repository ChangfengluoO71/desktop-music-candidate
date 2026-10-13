package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.CoroutineContext

data class LocalAudioMetadata(
    val codec: String?,
    val lossiness: AudioLossiness,
    val durationSeconds: Double?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int?,
    val discNumber: Int?,
)

fun interface LocalAudioMetadataReader {
    fun read(path: Path): LocalAudioMetadata
}

class JaudiotaggerLocalAudioMetadataReader : LocalAudioMetadataReader {
    override fun read(path: Path): LocalAudioMetadata {
        val file = AudioFileIO.read(path.toFile())
        val header = file.audioHeader
        val tag = file.tag
        fun value(key: FieldKey): String = runCatching { tag?.getFirst(key).orEmpty().trim() }.getOrDefault("")
        val artist = value(FieldKey.ARTIST)
        return LocalAudioMetadata(
            codec = header.encodingType?.takeIf(String::isNotBlank) ?: header.format,
            lossiness = if (header.isLossless) AudioLossiness.LOSSLESS else AudioLossiness.LOSSY,
            durationSeconds = header.preciseTrackLength.takeIf { it > 0.0 },
            bitrateKbps = header.bitRateAsNumber.toInt().takeIf { it > 0 },
            sampleRateHz = header.sampleRateAsNumber.takeIf { it > 0 },
            bitDepth = header.bitsPerSample.takeIf { it > 0 },
            title = value(FieldKey.TITLE),
            artists = artist.split(Regex("\\s*;\\s*")).filter(String::isNotBlank),
            album = value(FieldKey.ALBUM),
            albumArtist = value(FieldKey.ALBUM_ARTIST),
            trackNumber = parseTagNumber(value(FieldKey.TRACK)),
            discNumber = parseTagNumber(value(FieldKey.DISC_NO)),
        )
    }

    private fun parseTagNumber(value: String): Int? = value.substringBefore('/').trim().toIntOrNull()
}

/** Walks selected roots without following links and retains no audio payload in memory. */
class DesktopMusicScanner(
    private val store: LocalMusicStore,
    private val metadataReader: LocalAudioMetadataReader = JaudiotaggerLocalAudioMetadataReader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun scan(
        roots: List<Path>,
        onProgress: (LocalScanProgress) -> Unit = {},
    ): LocalMusicScanResult = withContext(ioDispatcher) {
        val rootErrors = mutableListOf<LocalScanError>()
        val plans = roots.distinctBy { rootIdentity(it) }.mapNotNull { input ->
            currentCoroutineContext().ensureActive()
            try {
                val root = store.registerRoot(input)
                val path = Path.of(root.path)
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    rootErrors += LocalScanError(LocalScanErrorKind.INVALID_ROOT, "选择的路径不是可扫描文件夹。")
                    null
                } else RootPlan(root, path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: AccessDeniedException) {
                rootErrors += LocalScanError(LocalScanErrorKind.ACCESS_DENIED, "没有读取该扫描目录的权限。")
                null
            } catch (missing: NoSuchFileException) {
                rootErrors += LocalScanError(LocalScanErrorKind.ROOT_MISSING, "扫描目录不存在。")
                null
            } catch (_: Exception) {
                rootErrors += LocalScanError(LocalScanErrorKind.INVALID_ROOT, "无法打开所选扫描目录。")
                null
            }
        }

        val scanContext = currentCoroutineContext()
        val counted = plans.map { plan -> plan to walkAudioFiles(plan.path, scanContext) { _, _ -> } }
        val total = counted.sumOf { (_, result) -> result.audioFiles }
        var completed = 0
        var scanned = 0
        var reused = 0
        var failures = 0
        var unsupported = 0

        counted.forEach { (plan, countResult) ->
            currentCoroutineContext().ensureActive()
            val scanId = UUID.randomUUID().toString()
            val scanResult = walkAudioFiles(
                root = plan.path,
                context = scanContext,
                onVisitFailure = { file, error ->
                    if (file.extension().lowercase(Locale.ROOT) in AUDIO_EXTENSIONS) {
                        val kind = when (error) {
                            is AccessDeniedException -> LocalScanErrorKind.ACCESS_DENIED
                            is NoSuchFileException -> LocalScanErrorKind.FILE_MISSING
                            else -> LocalScanErrorKind.TAG_READ_FAILED
                        }
                        val status = when (kind) {
                            LocalScanErrorKind.ACCESS_DENIED -> LocalMetadataStatus.ACCESS_DENIED
                            LocalScanErrorKind.FILE_MISSING -> LocalMetadataStatus.MISSING_FILE
                            else -> LocalMetadataStatus.DAMAGED
                        }
                        val summary = when (kind) {
                            LocalScanErrorKind.ACCESS_DENIED -> "没有读取此文件或目录的权限。"
                            LocalScanErrorKind.FILE_MISSING -> "扫描期间文件已被移动或删除。"
                            else -> "访问文件失败；原文件未修改。"
                        }
                        val normalized = file.toAbsolutePath().normalize()
                        val key = pathKey(normalized)
                        val fallback = filenameMetadata(file)
                        val errorTrack = errorTrack(
                            file, key, 0L, 0L, formatFor(file), fallback, status, kind, summary,
                        )
                        store.saveTrack(plan.root.path, scanId, errorTrack)
                        completed++
                        failures++
                        onProgress(LocalScanProgress(completed, total, errorTrack.filePath, plan.root.path))
                    }
                },
            ) { file, attributes ->
                scanContext.ensureActive()
                val track = scanOne(file, attributes, scanContext).also { result ->
                    if (result.second) reused++ else scanned++
                    if (result.first.error != null) failures++
                    if (result.first.metadataStatus == LocalMetadataStatus.UNSUPPORTED) unsupported++
                    store.saveTrack(plan.root.path, scanId, result.first)
                }.first
                completed++
                onProgress(LocalScanProgress(completed, total, track.filePath, plan.root.path))
            }
            if (countResult.complete && scanResult.complete) {
                store.finishRootScan(plan.root.path, scanId)
            } else {
                rootErrors += LocalScanError(
                    LocalScanErrorKind.ACCESS_DENIED,
                    "扫描期间部分目录不可访问；为避免误删索引，旧记录已保留。",
                )
            }
        }
        LocalMusicScanResult(
            tracks = store.loadTracks(),
            scannedFiles = scanned,
            reusedMetadata = reused,
            failedFiles = failures,
            unsupportedFiles = unsupported,
            rootErrors = rootErrors,
        )
    }

    private fun scanOne(
        path: Path,
        attributes: BasicFileAttributes,
        context: CoroutineContext,
    ): Pair<LocalTrack, Boolean> {
        val canonical = runCatching { path.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { path.toAbsolutePath().normalize() }
        val key = pathKey(canonical)
        val size = attributes.size().coerceAtLeast(0L)
        val modified = attributes.lastModifiedTime().toMillis()
        val existing = store.findTrack(key)
        if (existing != null && existing.fileSize == size && existing.lastModifiedMillis == modified) {
            return existing.copy(filePath = canonical.toString(), scannedAtMillis = System.currentTimeMillis()) to true
        }

        val format = formatFor(path)
        val fallback = filenameMetadata(path)
        val audioExtension = path.extension().lowercase(Locale.ROOT)
        if (audioExtension !in PARSABLE_EXTENSIONS) {
            return LocalTrack(
                id = trackId(key), pathKey = key, filePath = canonical.toString(), fileSize = size,
                lastModifiedMillis = modified, format = format, codec = null, lossiness = AudioLossiness.UNKNOWN,
                durationSeconds = null, bitrateKbps = null, sampleRateHz = null, bitDepth = null,
                title = fallback.title, titleSource = fallback.titleSource, artists = fallback.artists,
                album = "", albumArtist = "", trackNumber = null, discNumber = null,
                metadataStatus = LocalMetadataStatus.UNSUPPORTED,
                error = LocalScanError(LocalScanErrorKind.UNSUPPORTED_FORMAT, "此音频格式当前没有可用的解析器。"),
            ) to false
        }

        return try {
            context.ensureActive()
            val metadata = metadataReader.read(path)
            context.ensureActive()
            val title = metadata.title.ifBlank { fallback.title }
            val titleSource = if (metadata.title.isNotBlank()) LocalTitleSource.TAG else fallback.titleSource
            val artists = metadata.artists.ifEmpty { fallback.artists }
            val populatedFields = listOf(title, artists.joinToString(), metadata.album, metadata.albumArtist).count(String::isNotBlank)
            val status = when {
                populatedFields == 0 -> LocalMetadataStatus.MISSING
                populatedFields < 4 -> LocalMetadataStatus.PARTIAL
                else -> LocalMetadataStatus.READABLE
            }
            LocalTrack(
                id = trackId(key), pathKey = key, filePath = canonical.toString(), fileSize = size,
                lastModifiedMillis = modified, format = format, codec = metadata.codec,
                lossiness = metadata.lossiness, durationSeconds = metadata.durationSeconds,
                bitrateKbps = metadata.bitrateKbps, sampleRateHz = metadata.sampleRateHz,
                bitDepth = metadata.bitDepth, title = title, titleSource = titleSource,
                artists = artists, album = metadata.album, albumArtist = metadata.albumArtist,
                trackNumber = metadata.trackNumber, discNumber = metadata.discNumber,
                metadataStatus = status,
            ) to false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: AccessDeniedException) {
            errorTrack(path, key, size, modified, format, fallback, LocalMetadataStatus.ACCESS_DENIED,
                LocalScanErrorKind.ACCESS_DENIED, "没有读取此文件的权限。") to false
        } catch (missing: NoSuchFileException) {
            errorTrack(path, key, size, modified, format, fallback, LocalMetadataStatus.MISSING_FILE,
                LocalScanErrorKind.FILE_MISSING, "扫描期间文件已被移动或删除。") to false
        } catch (_: SecurityException) {
            errorTrack(path, key, size, modified, format, fallback, LocalMetadataStatus.ACCESS_DENIED,
                LocalScanErrorKind.ACCESS_DENIED, "没有读取此文件的权限。") to false
        } catch (_: Exception) {
            errorTrack(path, key, size, modified, format, fallback, LocalMetadataStatus.DAMAGED,
                LocalScanErrorKind.DAMAGED_AUDIO, "无法解析音频头或标签；原文件未修改。") to false
        }
    }

    private fun errorTrack(
        path: Path,
        key: String,
        size: Long,
        modified: Long,
        format: LocalAudioFormat,
        fallback: FilenameMetadata,
        status: LocalMetadataStatus,
        kind: LocalScanErrorKind,
        summary: String,
    ) = LocalTrack(
        id = trackId(key), pathKey = key, filePath = path.toAbsolutePath().normalize().toString(),
        fileSize = size, lastModifiedMillis = modified, format = format, codec = null,
        lossiness = AudioLossiness.UNKNOWN, durationSeconds = null, bitrateKbps = null,
        sampleRateHz = null, bitDepth = null, title = fallback.title, titleSource = fallback.titleSource,
        artists = fallback.artists, album = "", albumArtist = "", trackNumber = null,
        discNumber = null, metadataStatus = status, error = LocalScanError(kind, summary),
    )

    private fun walkAudioFiles(
        root: Path,
        context: CoroutineContext,
        onVisitFailure: (Path, IOException) -> Unit = { _, _ -> },
        onAudioFile: (Path, BasicFileAttributes) -> Unit,
    ): WalkResult {
        var count = 0
        var complete = true
        val visitedDirectories = HashSet<Any>()
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (Files.isSymbolicLink(dir) || attrs.isSymbolicLink || attrs.isOther) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val identity = attrs.fileKey() ?: pathKey(dir.toAbsolutePath().normalize())
                    if (!visitedDirectories.add(identity)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && file.extension().lowercase(Locale.ROOT) in AUDIO_EXTENSIONS) {
                        count++
                        onAudioFile(file, attrs)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    context.ensureActive()
                    complete = false
                    if (file.extension().lowercase(Locale.ROOT) in AUDIO_EXTENSIONS) {
                        count++
                        onVisitFailure(file, error)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: AccessDeniedException) {
            complete = false
        } catch (_: IOException) {
            complete = false
        }
        return WalkResult(count, complete)
    }

    private fun filenameMetadata(path: Path): FilenameMetadata {
        val fileName = path.fileName?.toString().orEmpty().substringBeforeLast('.', path.fileName?.toString().orEmpty())
        val parts = fileName.split(" - ", limit = 2)
        return FilenameMetadata(
            title = parts.firstOrNull().orEmpty().trim().ifBlank { fileName },
            titleSource = if (fileName.isBlank()) LocalTitleSource.UNKNOWN else LocalTitleSource.FILENAME,
            artists = parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
        )
    }

    private fun rootIdentity(path: Path): String = path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
    private fun Path.extension(): String = fileName?.toString()?.substringAfterLast('.', "").orEmpty()

    private fun formatFor(path: Path): LocalAudioFormat = when (path.extension().lowercase(Locale.ROOT)) {
        "mp3" -> LocalAudioFormat.MP3
        "flac" -> LocalAudioFormat.FLAC
        "wav" -> LocalAudioFormat.WAV
        "m4a", "mp4" -> LocalAudioFormat.M4A
        "aac" -> LocalAudioFormat.AAC
        else -> LocalAudioFormat.OTHER
    }

    private data class RootPlan(val root: LocalMusicRoot, val path: Path)
    private data class WalkResult(val audioFiles: Int, val complete: Boolean)
    private data class FilenameMetadata(
        val title: String,
        val titleSource: LocalTitleSource,
        val artists: List<String>,
    )

    companion object {
        private val PARSABLE_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "mp4")
        private val AUDIO_EXTENSIONS = PARSABLE_EXTENSIONS + setOf("aac", "ogg", "opus", "wma", "ape", "wv", "aiff")

        internal fun pathKey(path: Path): String = path.toAbsolutePath().normalize().toString()
            .replace('\\', '/')
            .lowercase(Locale.ROOT)

        private fun trackId(pathKey: String): String = sha256(pathKey.toByteArray(Charsets.UTF_8))

        private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
