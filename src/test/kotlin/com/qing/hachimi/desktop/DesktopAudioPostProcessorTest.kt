package com.qing.hachimi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

class DesktopAudioPostProcessorTest {
    @Test
    fun lyricsVariantsAreWrittenSeparatelyWithoutLosingWordTiming() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-lyrics")
        val audio = root.resolve("Song.mp3")
        Files.writeString(audio, "local synthetic audio fixture")
        val task = task(audio, AudioPostProcessingOptions(
            downloadLyrics = true,
            saveTranslatedLyrics = true,
            saveRomanizedLyrics = true,
            saveWordLevelLyrics = true,
        ))
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ ->
            DesktopPostProcessingData(lyrics = DesktopLyrics(
                lrc = "[00:01.00]hello",
                translation = "[00:01.00]你好",
                romanization = "[00:01.00]ni hao",
                wordLevel = "[0,1000](0,300,0)hel(300,700,0)lo",
            ))
        })

        val result = processor.process(task, null)

        assertEquals(PostProcessingStatus.COMPLETED, result.status, result.message)
        assertEquals("[ti:Song]\n[ar:Artist]\n[al:Album]\n\n[00:01.00]hello", Files.readString(root.resolve("Song.lrc")))
        assertTrue(Files.readString(root.resolve("Song.tl.lrc")).contains("[00:01.00]你好"))
        assertTrue(Files.readString(root.resolve("Song.roma.lrc")).contains("[00:01.00]ni hao"))
        assertEquals("[0,1000](0,300,0)hel(300,700,0)lo", Files.readString(root.resolve("Song.yrc")))
        assertFalse(Files.readString(root.resolve("Song.yrc")).contains("[00:01.00]hello"))
    }

    @Test
    fun missingLyricsDoNotCreateAnEmptySidecarOrFailTheAudio() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-no-lyrics")
        val audio = root.resolve("Silent.mp3")
        val original = "audio bytes"
        Files.writeString(audio, original)
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ -> DesktopPostProcessingData() })

        val result = processor.process(task(audio, AudioPostProcessingOptions(downloadLyrics = true)), null)

        assertEquals(PostProcessingStatus.COMPLETED, result.status, result.message)
        assertFalse(Files.exists(root.resolve("Silent.lrc")))
        assertEquals(original, Files.readString(audio))
    }

    @Test
    fun existingUserSidecarIsNotOverwritten() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-sidecar-conflict")
        val audio = root.resolve("Song.mp3")
        Files.writeString(audio, "audio")
        val sidecar = root.resolve("Song.lrc")
        Files.writeString(sidecar, "user lyric")
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ ->
            DesktopPostProcessingData(lyrics = DesktopLyrics(lrc = "[00:01.00]generated"))
        })

        val result = processor.process(task(audio, AudioPostProcessingOptions(downloadLyrics = true)), null)

        assertEquals(PostProcessingStatus.WARNING, result.status)
        assertEquals("user lyric", Files.readString(sidecar))
    }

    @Test
    fun tagFailureLeavesDownloadedAudioUntouchedAndCanBeReportedAsRecoverable() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-tag-failure")
        val audio = root.resolve("Broken.mp3")
        val original = ByteArray(64) { it.toByte() }
        Files.write(audio, original)
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ -> DesktopPostProcessingData() })

        val result = processor.process(task(audio, AudioPostProcessingOptions(writeAudioTags = true)), null)

        assertEquals(PostProcessingStatus.FAILED, result.status)
        assertTrue(Files.readAllBytes(audio).contentEquals(original))
        assertFalse(Files.list(root).use { paths ->
            paths.anyMatch { it.fileName.toString().startsWith(".hstage-") || it.fileName.toString().contains("hachimi-stage") }
        })
    }

    @Test
    fun mp3TagsAndCoverAreReadBackAndCompressedFramesRemainIdentical() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-mp3-tags")
        val audio = root.resolve("Song.mp3")
        Files.write(audio, fixture("synthetic-tone.mp3"))
        val payloadBefore = AudioPayloadIntegrity.digest(audio, "mp3")
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ ->
            DesktopPostProcessingData(
                lyrics = DesktopLyrics(
                    lrc = "[00:00.00]local lyrics",
                    translation = "[00:00.00]本地歌词",
                    romanization = "[00:00.00]ben di ge ci",
                ),
                tags = DesktopTagMetadata(
                    albumArtist = "Album Artist",
                    trackNumber = 3,
                    trackTotal = 12,
                    discNumber = 1,
                    discTotal = 2,
                    releaseYear = 2024,
                    aliases = listOf("Alias"),
                    translatedTitles = listOf("Translated title"),
                    lyricists = listOf("Writer"),
                    composers = listOf("Composer"),
                    arrangers = listOf("Arranger"),
                    producers = listOf("Producer"),
                    comment = "Local fixture tag",
                ),
                coverBytes = onePixelPng(),
                coverMimeType = "image/png",
            )
        })

        val result = processor.process(
            task(audio, AudioPostProcessingOptions(
                writeAudioTags = true,
                embedCoverArt = true,
                embedLyrics = true,
                downloadLyrics = true,
                saveTranslatedLyrics = true,
                saveRomanizedLyrics = true,
            )),
            null,
        )

        assertEquals(PostProcessingStatus.COMPLETED, result.status, result.message)
        val tag = requireNotNull(AudioFileIO.read(audio.toFile()).tag)
        assertEquals("Song", tag.getFirst(FieldKey.TITLE))
        assertEquals("Artist", tag.getFirst(FieldKey.ARTIST))
        assertEquals("Album", tag.getFirst(FieldKey.ALBUM))
        assertEquals("Album Artist", tag.getFirst(FieldKey.ALBUM_ARTIST))
        assertEquals("3", tag.getFirst(FieldKey.TRACK))
        assertEquals("12", tag.getFirst(FieldKey.TRACK_TOTAL))
        assertEquals("1", tag.getFirst(FieldKey.DISC_NO))
        assertEquals("2", tag.getFirst(FieldKey.DISC_TOTAL))
        assertEquals("2024", tag.getFirst(FieldKey.YEAR))
        assertEquals("Alias / Translated title", tag.getFirst(FieldKey.SUBTITLE))
        assertEquals("Writer", tag.getFirst(FieldKey.LYRICIST))
        assertEquals("Composer", tag.getFirst(FieldKey.COMPOSER))
        assertEquals("Arranger", tag.getFirst(FieldKey.ARRANGER))
        assertEquals("Producer", tag.getFirst(FieldKey.PRODUCER))
        assertEquals("Local fixture tag", tag.getFirst(FieldKey.COMMENT))
        assertTrue(tag.getFirst(FieldKey.LYRICS).contains("[00:00.00]local lyrics"))
        assertTrue(tag.getFirst(FieldKey.LYRICS).contains("[00:00.00]本地歌词"))
        assertTrue(Files.readString(root.resolve("Song.lrc")).contains("[00:00.00]local lyrics"))
        assertTrue(Files.readString(root.resolve("Song.tl.lrc")).contains("[00:00.00]本地歌词"))
        assertTrue(Files.readString(root.resolve("Song.roma.lrc")).contains("[00:00.00]ben di ge ci"))
        assertTrue(tag.artworkList.any { it.binaryData.contentEquals(onePixelPng()) })
        assertEquals(payloadBefore, AudioPayloadIntegrity.digest(audio, "mp3"))
    }

    @Test
    fun flacTagsAreReadBackAndCompressedFramesRemainIdentical() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-flac-tags")
        val audio = root.resolve("Song.flac")
        Files.write(audio, fixture("synthetic-tone.flac"))
        val payloadBefore = AudioPayloadIntegrity.digest(audio, "flac")
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ -> DesktopPostProcessingData() })

        val result = processor.process(task(audio, AudioPostProcessingOptions(writeAudioTags = true)), null)

        assertEquals(PostProcessingStatus.COMPLETED, result.status, result.message)
        val tag = requireNotNull(AudioFileIO.read(audio.toFile()).tag)
        assertEquals("Song", tag.getFirst(FieldKey.TITLE))
        assertEquals("Artist", tag.getFirst(FieldKey.ARTIST))
        assertEquals("Album", tag.getFirst(FieldKey.ALBUM))
        assertEquals(payloadBefore, AudioPayloadIntegrity.digest(audio, "flac"))
    }

    @Test
    fun unsupportedAudioFormatProducesWarningAndKeepsTheOriginalFile() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-tag-unsupported")
        val audio = root.resolve("Song.wav")
        val original = byteArrayOf(1, 2, 3, 4, 5)
        Files.write(audio, original)
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ -> DesktopPostProcessingData() })

        val result = processor.process(task(audio, AudioPostProcessingOptions(writeAudioTags = true)), null)

        assertEquals(PostProcessingStatus.WARNING, result.status)
        assertTrue(Files.readAllBytes(audio).contentEquals(original))
    }

    @Test
    fun orphanedStagingFileFromInterruptedTagWriteIsCleanedOnRetry() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("hachimi-tag-recovery")
        val audio = root.resolve("Song.mp3")
        Files.write(audio, fixture("synthetic-tone.mp3"))
        val task = task(audio, AudioPostProcessingOptions(writeAudioTags = true)).copy(id = "recoverable-task")
        val orphan = root.resolve(".hachimi-stage-${task.id}-interrupted.mp3")
        val currentOrphan = root.resolve(".hstage-${task.id.take(10)}-interrupted.mp3")
        Files.writeString(orphan, "interrupted staging data")
        Files.writeString(currentOrphan, "interrupted staging data")
        val processor = DesktopAudioPostProcessor(DesktopPostProcessingDataSource { _, _ -> DesktopPostProcessingData() })

        val result = processor.process(task, null)

        assertEquals(PostProcessingStatus.COMPLETED, result.status, result.message)
        assertFalse(Files.exists(orphan))
        assertFalse(Files.exists(currentOrphan))
    }

    private fun onePixelPng() = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/audio-fixtures/$name")).use { it.readBytes() }

    private fun task(path: java.nio.file.Path, options: AudioPostProcessingOptions) = DownloadTask(
        songId = 1L,
        songName = "Song",
        artists = "Artist",
        album = "Album",
        requestedQuality = "exhigh",
        filePath = path.toString(),
        status = DownloadTaskStatus.COMPLETED,
        postProcessingOptions = options,
    )
}
