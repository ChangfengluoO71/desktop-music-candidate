package com.qing.hachimi.desktop

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class DesktopLyrics(
    val lrc: String = "",
    val translation: String = "",
    val romanization: String = "",
    val wordLevel: String = "",
    val wordLevelTranslation: String = "",
    val wordLevelRomanization: String = "",
)

data class DesktopTagMetadata(
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val releaseYear: Int? = null,
    val aliases: List<String> = emptyList(),
    val translatedTitles: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val arrangers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val comment: String? = null,
)

data class DesktopPostProcessingData(
    val lyrics: DesktopLyrics? = null,
    val tags: DesktopTagMetadata = DesktopTagMetadata(),
    val coverBytes: ByteArray? = null,
    val coverMimeType: String? = null,
    val coverError: String? = null,
)

fun interface DesktopPostProcessingDataSource {
    suspend fun load(task: DownloadTask, musicU: String?): DesktopPostProcessingData
}

data class PostProcessingResult(
    val status: PostProcessingStatus,
    val message: String? = null,
) {
    init { require(status in setOf(PostProcessingStatus.COMPLETED, PostProcessingStatus.WARNING, PostProcessingStatus.FAILED)) }
}

fun interface DownloadPostProcessor {
    suspend fun process(task: DownloadTask, musicU: String?): PostProcessingResult
}

private object UnavailablePostProcessor : DownloadPostProcessor {
    override suspend fun process(task: DownloadTask, musicU: String?) = PostProcessingResult(
        PostProcessingStatus.WARNING,
        "附加信息处理器不可用；已下载的音频文件仍然保留。",
    )
}

/** Builds optional lyric files and transactionally writes MP3/FLAC tags after audio download. */
class DesktopAudioPostProcessor(
    private val dataSource: DesktopPostProcessingDataSource,
) : DownloadPostProcessor {
    override suspend fun process(task: DownloadTask, musicU: String?): PostProcessingResult {
        if (!task.postProcessingOptions.requested) return PostProcessingResult(PostProcessingStatus.COMPLETED)
        val data = dataSource.load(task, musicU)
        return process(task, data)
    }

    internal fun process(task: DownloadTask, data: DesktopPostProcessingData): PostProcessingResult {
        val target = Path.of(task.filePath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(target)) {
            return PostProcessingResult(PostProcessingStatus.FAILED, "已下载音频文件不存在，无法处理附加信息。")
        }
        val options = task.postProcessingOptions
        val warnings = mutableListOf<String>()
        try {
            if (options.downloadLyrics || options.saveTranslatedLyrics || options.saveRomanizedLyrics || options.saveWordLevelLyrics) {
                writeLyrics(target, task, data.lyrics, options, warnings)
            }
        } catch (_: Exception) {
            warnings += "歌词文件未能保存；已下载音频仍然保留。"
        }

        if (options.writeAudioTags || options.embedLyrics || options.embedCoverArt) {
            try {
                writeTagsTransactionally(target, task, data, warnings)
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException("附加信息处理已取消")
            } catch (_: Exception) {
                return PostProcessingResult(
                    PostProcessingStatus.FAILED,
                    (warnings + "音频标签写入失败。原始下载文件已保留，可在任务页重试处理。").joinToString(" "),
                )
            }
        }
        return if (warnings.isEmpty()) PostProcessingResult(PostProcessingStatus.COMPLETED)
        else PostProcessingResult(PostProcessingStatus.WARNING, warnings.distinct().joinToString(" "))
    }

    private fun writeLyrics(
        audio: Path,
        task: DownloadTask,
        lyrics: DesktopLyrics?,
        options: AudioPostProcessingOptions,
        warnings: MutableList<String>,
    ) {
        if (lyrics == null) return
        val stem = audio.fileName.toString().substringBeforeLast('.', audio.fileName.toString())
        val base = audio.resolveSibling(stem)
        fun header(content: String) = buildString {
            append("[ti:${safeLrcValue(task.songName)}]\n")
            append("[ar:${safeLrcValue(task.artists)}]\n")
            append("[al:${safeLrcValue(task.album)}]\n\n")
            append(content)
        }

        if (options.downloadLyrics && lyrics.lrc.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.lrc"), header(lyrics.lrc))) {
                warnings += "已有同名 LRC 文件，未覆盖原文件。"
            }
        }
        if (options.saveTranslatedLyrics && lyrics.translation.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.tl.lrc"), header(lyrics.translation))) {
                warnings += "已有同名翻译歌词文件，未覆盖原文件。"
            }
        }
        if (options.saveRomanizedLyrics && lyrics.romanization.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.roma.lrc"), header(lyrics.romanization))) {
                warnings += "已有同名罗马音歌词文件，未覆盖原文件。"
            }
        }
        if (options.saveWordLevelLyrics && lyrics.wordLevel.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.yrc"), lyrics.wordLevel)) {
                warnings += "已有同名逐字歌词文件，未覆盖原文件。"
            }
        }
        if (options.saveTranslatedLyrics && options.saveWordLevelLyrics && lyrics.wordLevelTranslation.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.tl.yrc"), lyrics.wordLevelTranslation)) {
                warnings += "已有同名逐字翻译文件，未覆盖原文件。"
            }
        }
        if (options.saveRomanizedLyrics && options.saveWordLevelLyrics && lyrics.wordLevelRomanization.isNotBlank()) {
            if (!writeSidecar(base.resolveSibling("${base.fileName}.roma.yrc"), lyrics.wordLevelRomanization)) {
                warnings += "已有同名逐字罗马音文件，未覆盖原文件。"
            }
        }
    }

    private fun writeSidecar(target: Path, content: String): Boolean {
        if (content.isBlank()) return true
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (Files.exists(target)) return Files.readAllBytes(target).contentEquals(bytes)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
            Files.move(temp, target)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return Files.readAllBytes(target).contentEquals(bytes)
            }
            return true
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun writeTagsTransactionally(
        target: Path,
        task: DownloadTask,
        data: DesktopPostProcessingData,
        warnings: MutableList<String>,
    ) {
        val extension = target.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in setOf("mp3", "flac")) {
            warnings += "音频格式不支持写标签；已保留原始音频。"
            return
        }
        AudioFileIO.read(target.toFile())
        val payloadBefore = AudioPayloadIntegrity.digest(target, extension)
        val attributesBefore = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java)
        cleanupStaleStageFiles(target, task.id)
        val staging = Files.createTempFile(target.parent, stagingPrefix(task.id), ".$extension")
        try {
            Files.copy(target, staging, StandardCopyOption.REPLACE_EXISTING)
            val audio = AudioFileIO.read(staging.toFile())
            val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }
            val expectedOptional = linkedMapOf<FieldKey, String>()
            fun setOptionalAndTrack(key: FieldKey, value: String?) {
                if (value.isNullOrBlank()) return
                val warningCount = warnings.size
                setOptional(tag, key, value, warnings)
                if (warnings.size == warningCount) expectedOptional[key] = value
            }
            setRequired(tag, FieldKey.TITLE, task.songName)
            setRequired(tag, FieldKey.ARTIST, task.artists)
            setRequired(tag, FieldKey.ALBUM, task.album)

            setOptionalAndTrack(FieldKey.ALBUM_ARTIST, data.tags.albumArtist.ifBlank { task.albumArtist })
            setOptionalAndTrack(FieldKey.TRACK, (data.tags.trackNumber ?: task.trackNumber)?.toString())
            setOptionalAndTrack(FieldKey.TRACK_TOTAL, (data.tags.trackTotal ?: task.trackTotal)?.toString())
            setOptionalAndTrack(FieldKey.DISC_NO, (data.tags.discNumber ?: task.discNumber)?.toString())
            setOptionalAndTrack(FieldKey.DISC_TOTAL, (data.tags.discTotal ?: task.discTotal)?.toString())
            setOptionalAndTrack(FieldKey.YEAR, (data.tags.releaseYear ?: task.releaseYear)?.toString())
            setOptionalAndTrack(
                FieldKey.SUBTITLE,
                (data.tags.aliases + task.aliases + data.tags.translatedTitles + task.translatedTitles)
                    .filter(String::isNotBlank).distinct().joinToString(" / ").takeIf(String::isNotBlank),
            )
            setOptionalAndTrack(FieldKey.LYRICIST, data.tags.lyricists.joinToString(" / ").takeIf(String::isNotBlank))
            setOptionalAndTrack(FieldKey.COMPOSER, data.tags.composers.joinToString(" / ").takeIf(String::isNotBlank))
            setOptionalAndTrack(FieldKey.ARRANGER, data.tags.arrangers.joinToString(" / ").takeIf(String::isNotBlank))
            setOptionalAndTrack(FieldKey.PRODUCER, data.tags.producers.joinToString(" / ").takeIf(String::isNotBlank))
            setOptionalAndTrack(FieldKey.COMMENT, data.tags.comment)

            val embeddedLyrics = buildEmbeddedLyrics(task, data.lyrics, task.postProcessingOptions)
            setOptionalAndTrack(FieldKey.LYRICS, embeddedLyrics)
            val cover = data.coverBytes
            if (task.postProcessingOptions.embedCoverArt && cover != null && cover.isNotEmpty()) {
                setCover(tag, cover, data.coverMimeType)
            } else if (task.postProcessingOptions.embedCoverArt) {
                warnings += data.coverError?.let { "封面未能获取：$it 音频仍已保留。" }
                    ?: "歌曲和任务记录都没有可用封面地址，未写入封面标签。"
            }
            audio.commit()

            val readBack = AudioFileIO.read(staging.toFile())
            val verifiedTag = readBack.tag ?: throw IOException("音频标签读回失败")
            verifyRequiredField(verifiedTag, FieldKey.TITLE, task.songName)
            verifyRequiredField(verifiedTag, FieldKey.ARTIST, task.artists)
            verifyRequiredField(verifiedTag, FieldKey.ALBUM, task.album)
            expectedOptional.forEach { (key, expected) ->
                if (verifiedTag.getFirst(key).trim() != expected.trim()) {
                    warnings += "${key.name} 标签未能通过读回校验。"
                }
            }
            if (!embeddedLyrics.isNullOrBlank() && verifiedTag.getFirst(FieldKey.LYRICS) != embeddedLyrics) {
                warnings += "此音频格式未能读回嵌入歌词。"
            }
            if (task.postProcessingOptions.embedCoverArt && cover != null && cover.isNotEmpty()) {
                if (verifiedTag.artworkList.none { it.binaryData.contentEquals(cover) }) {
                    warnings += "封面未能通过读回校验。"
                }
            }
            val payloadAfter = AudioPayloadIntegrity.digest(staging, extension)
            if (payloadBefore != payloadAfter) throw IOException("标签修改后音频载荷校验不一致")

            val current = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java)
            if (attributesBefore.size() != current.size() ||
                attributesBefore.lastModifiedTime() != current.lastModifiedTime() ||
                attributesBefore.fileKey() != current.fileKey()
            ) throw IOException("原始音频在标签处理期间发生变化")

            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun cleanupStaleStageFiles(target: Path, taskId: String) {
        listOf(stagingPrefix(taskId) + "*", ".hachimi-stage-$taskId-*").forEach { pattern ->
            Files.newDirectoryStream(target.parent, pattern).use { entries ->
                entries.forEach { candidate ->
                    if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(candidate)
                    ) Files.deleteIfExists(candidate)
                }
            }
        }
    }

    private fun stagingPrefix(taskId: String): String = ".hstage-${taskId.take(10)}-"

    private fun buildEmbeddedLyrics(task: DownloadTask, lyrics: DesktopLyrics?, options: AudioPostProcessingOptions): String? {
        if (!options.embedLyrics || lyrics?.lrc.isNullOrBlank()) return null
        return buildString {
            append("[ti:${safeLrcValue(task.songName)}]\n[ar:${safeLrcValue(task.artists)}]\n[al:${safeLrcValue(task.album)}]\n\n")
            append(lyrics.lrc)
            if (options.saveTranslatedLyrics && lyrics.translation.isNotBlank()) append("\n\n${lyrics.translation}")
            if (options.saveRomanizedLyrics && lyrics.romanization.isNotBlank()) append("\n\n${lyrics.romanization}")
        }
    }

    private fun setRequired(tag: Tag, key: FieldKey, value: String) {
        tag.setField(tag.createField(key, value))
    }

    private fun setOptional(tag: Tag, key: FieldKey, value: String?, warnings: MutableList<String>) {
        if (value.isNullOrBlank()) return
        runCatching { tag.setField(tag.createField(key, value)) }
            .onFailure { warnings += "${key.name} 标签格式不支持，已跳过。" }
    }

    private fun setCover(tag: Tag, bytes: ByteArray, reportedMime: String?) {
        val mime = artworkMime(bytes, reportedMime)
        when (tag) {
            is FlacTag -> tag.setField(tag.createArtworkField(bytes, 3, mime, "", 0, 0, 0, 0))
            else -> ArtworkFactory.getNew().also { artwork ->
                artwork.binaryData = bytes
                artwork.mimeType = mime
                artwork.pictureType = 3
                tag.setField(artwork)
            }
        }
    }

    private fun verifyRequiredField(tag: Tag, key: FieldKey, expected: String) {
        if (tag.getFirst(key).trim() != expected.trim()) throw IOException("${key.name} 标签读回失败")
    }

    private fun artworkMime(bytes: ByteArray, reported: String?): String = when {
        bytes.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "image/jpeg"
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        else -> reported?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun safeLrcValue(value: String): String = value.replace('\r', ' ').replace('\n', ' ').take(300)
}

/** Hashes compressed audio payload while ignoring mutable MP3/FLAC metadata blocks. */
internal object AudioPayloadIntegrity {
    fun digest(path: Path, extension: String): String {
        val size = Files.size(path)
        val range = when (extension.lowercase()) {
            "mp3" -> mp3PayloadRange(path, size)
            "flac" -> flacPayloadRange(path, size)
            else -> throw IOException("不支持校验此音频载荷")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            input.skipNBytes(range.first)
            var remaining = range.last - range.first + 1
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("音频载荷意外结束")
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mp3PayloadRange(path: Path, size: Long): LongRange {
        if (size < 8L) throw IOException("MP3 文件过短")
        val head = ByteArray(minOf(size, 10L).toInt())
        FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.read(ByteBuffer.wrap(head)) }
        var start = 0L
        if (head.size >= 10 && head.copyOfRange(0, 3).contentEquals("ID3".encodeToByteArray())) {
            val tagSize = (head[6].toInt() and 0x7f shl 21) or
                (head[7].toInt() and 0x7f shl 14) or
                (head[8].toInt() and 0x7f shl 7) or
                (head[9].toInt() and 0x7f)
            start = 10L + tagSize + if (head[5].toInt() and 0x10 != 0) 10 else 0
        }
        val scanEnd = minOf(size - 4, start + 256 * 1024)
        val scan = ByteArray((scanEnd - start + 4).coerceAtLeast(0).toInt())
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            channel.read(ByteBuffer.wrap(scan))
        }
        var frameStart = -1L
        for (offset in 0 until (scan.size - 3).coerceAtLeast(0)) {
            if ((scan[offset].toInt() and 0xff) == 0xff && (scan[offset + 1].toInt() and 0xe0) == 0xe0) {
                val version = scan[offset + 1].toInt() shr 3 and 0x03
                val layer = scan[offset + 1].toInt() shr 1 and 0x03
                val bitrate = scan[offset + 2].toInt() shr 4 and 0x0f
                val sampleRate = scan[offset + 2].toInt() shr 2 and 0x03
                if (version != 1 && layer != 0 && bitrate !in setOf(0, 15) && sampleRate != 3) {
                    frameStart = start + offset
                    break
                }
            }
        }
        if (frameStart < 0L) throw IOException("找不到 MP3 音频帧")
        var end = size
        val tailLength = minOf(size, 160L).toInt()
        val tail = ByteArray(tailLength)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(size - tailLength)
            channel.read(ByteBuffer.wrap(tail))
        }
        if (tail.size >= 128 && tail.copyOfRange(tail.size - 128, tail.size - 125).contentEquals("TAG".encodeToByteArray())) end -= 128
        if (end - 32 >= frameStart) {
            val ape = ByteArray(8)
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.position(end - 32)
                channel.read(ByteBuffer.wrap(ape))
            }
            if (ape.contentEquals("APETAGEX".encodeToByteArray())) {
                val footer = ByteArray(4)
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    channel.position(end - 20)
                    channel.read(ByteBuffer.wrap(footer))
                }
                val apeSize = (footer[0].toLong() and 0xff) or ((footer[1].toLong() and 0xff) shl 8) or
                    ((footer[2].toLong() and 0xff) shl 16) or ((footer[3].toLong() and 0xff) shl 24)
                if (apeSize in 32..(end - frameStart)) end -= apeSize
            }
        }
        if (end <= frameStart) throw IOException("MP3 没有有效音频载荷")
        return frameStart until end
    }

    private fun flacPayloadRange(path: Path, size: Long): LongRange {
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        channel.use {
            val magic = ByteArray(4)
            channel.read(ByteBuffer.wrap(magic))
            if (!magic.contentEquals("fLaC".encodeToByteArray())) throw IOException("FLAC 文件头无效")
            var position = 4L
            var last = false
            while (!last) {
                if (position + 4 > size) throw IOException("FLAC 元数据区截断")
                val block = ByteArray(4)
                channel.position(position)
                channel.read(ByteBuffer.wrap(block))
                last = (block[0].toInt() and 0x80) != 0
                val length = ((block[1].toLong() and 0xff) shl 16) or
                    ((block[2].toLong() and 0xff) shl 8) or (block[3].toLong() and 0xff)
                position += 4 + length
                if (position > size) throw IOException("FLAC 元数据长度越界")
            }
            if (position >= size) throw IOException("FLAC 音频帧缺失")
            return position until size
        }
    }
}
