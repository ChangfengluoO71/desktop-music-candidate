package com.qing.hachimi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionImportStoreTest {
    @Test
    fun taskCollectionMembershipAndPostProcessingSettingsAreBackwardCompatible() {
        val root = Files.createTempDirectory("collection-task-store")
        val store = DownloadTaskStore(root.resolve("tasks.json"))
        val task = DownloadTask(
            id = "task-collection",
            songId = 12L,
            songName = "Song",
            artists = "Artist",
            album = "Album",
            requestedQuality = "lossless",
            filePath = root.resolve("song.flac").toString(),
            collectionImportIds = listOf("first", "second"),
            trackNumber = 4,
            trackTotal = 9,
            postProcessingOptions = AudioPostProcessingOptions(downloadLyrics = true, writeAudioTags = true),
        )
        val settings = DesktopSettings(
            defaultDownloadDirectory = root.toString(),
            folderGrouping = FolderGrouping.PLAYLIST,
            downloadLyrics = true,
            saveTranslatedLyrics = true,
        )

        store.save(listOf(task), settings)
        val restored = store.load()

        assertEquals(listOf(task), restored.tasks)
        assertEquals(settings, restored.settings)
        val legacy = root.resolve("legacy.json")
        Files.writeString(legacy, """{"tasks":[],"settings":{"defaultDownloadDirectory":"${root.toString().replace("\\", "\\\\")}"}}""")
        val oldSnapshot = DownloadTaskStore(legacy).load()
        assertEquals(FolderGrouping.NONE, oldSnapshot.settings.folderGrouping)
        assertEquals(false, oldSnapshot.settings.downloadLyrics)
    }

    @Test
    fun collectionMembershipAndPartialStatusesSurviveRestartWithoutSessionData() {
        val root = Files.createTempDirectory("collection-store-test")
        val file = root.resolve("collections.json")
        val record = CollectionImport(
            id = "import-1",
            sourceType = CollectionSourceType.PLAYLIST,
            sourceId = 123L,
            sourceName = "Playlist",
            requestedQuality = "lossless",
            downloadDirectory = root.toString(),
            createdAtMillis = 1L,
            items = listOf(
                CollectionImportItem(1, 10L, "Song", "Artist", "Album", CollectionItemStatus.QUEUED, "task-1"),
                CollectionImportItem(2, null, "Unknown track", "", "", CollectionItemStatus.MISSING),
            ),
        )

        CollectionImportStore(file).save(listOf(record))
        val restored = CollectionImportStore(file).load()

        assertEquals(listOf(record), restored)
        val json = Files.readString(file)
        assertTrue(!json.contains("MUSIC_U", ignoreCase = true))
        assertTrue(!json.contains("cookie", ignoreCase = true))
        assertTrue(!json.contains("session-secret"))
    }

    @Test
    fun invalidCollectionDocumentDoesNotPreventStartup() {
        val root = Files.createTempDirectory("collection-store-corrupt")
        val file = root.resolve("collections.json")
        Files.writeString(file, "{ broken")

        assertEquals(emptyList(), CollectionImportStore(file).load())
        assertTrue(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("collections.json.corrupt-") } })
    }
}
