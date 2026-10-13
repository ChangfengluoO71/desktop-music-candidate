package com.qing.hachimi.desktop

import java.nio.file.Files
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTaskStoreTest {
    @Test
    fun `round trip stores tasks and settings but redacts signed URLs and credentials`() {
        val root = Files.createTempDirectory("hachimi-task-store")
        val path = root.resolve("tasks.json")
        val store = DownloadTaskStore(path)
        val task = task().copy(
            errorType = DownloadErrorType.NETWORK,
            errorSummary = "failed https://cdn.example/audio?auth=private-secret MUSIC_U=private-cookie",
        )
        val settings = DesktopSettings(
            defaultDownloadDirectory = root.toString(),
            maxConcurrentDownloads = 3,
            defaultQuality = "lossless",
            fileConflictPolicy = FileConflictPolicy.FAIL_IF_EXISTS,
            coverDirectoryMode = CoverDirectoryMode.CUSTOM_DIRECTORY,
            customCoverDirectory = root.resolve("covers").toString(),
        )

        store.save(listOf(task), settings)

        val loaded = store.load()
        val raw = Files.readString(path)
        assertEquals(task.id, loaded.tasks.single().id)
        assertEquals(settings, loaded.settings)
        assertTrue(raw.contains("\"audioOutputMode\":\"SHARED\""))
        assertFalse(raw.contains("private-secret"))
        assertFalse(raw.contains("private-cookie"))
        assertFalse(raw.contains("https://cdn.example"))
        assertFalse(raw.contains("MUSIC_U"))
    }

    @Test
    fun `legacy settings default to beside media cover directory`() {
        val root = Files.createTempDirectory("hachimi-task-store-legacy-cover")
        val file = root.resolve("tasks.json")
        Files.writeString(
            file,
            """{"schemaVersion":2,"tasks":[],"settings":{"defaultDownloadDirectory":"$root","maxConcurrentDownloads":2,"defaultQuality":"exhigh"}}""",
        )

        val settings = DownloadTaskStore(file).load().settings

        assertEquals(CoverDirectoryMode.BESIDE_MEDIA, settings.coverDirectoryMode)
        assertEquals("", settings.customCoverDirectory)
    }

    @Test
    fun `legacy settings without audio output mode default to shared`() {
        val root = Files.createTempDirectory("dmc-task-store-legacy-audio-output")
        val file = root.resolve("tasks.json")
        Files.writeString(file, JSONObject()
            .put("schemaVersion", 2)
            .put("tasks", org.json.JSONArray())
            .put("settings", JSONObject()
                .put("defaultDownloadDirectory", root.toString())
                .put("maxConcurrentDownloads", 3)
                .put("defaultQuality", "lossless"))
            .toString())

        val settings = DownloadTaskStore(file).load().settings

        assertEquals(AudioOutputMode.SHARED, settings.audioOutputMode)
        assertEquals(3, settings.maxConcurrentDownloads)
        assertEquals("lossless", settings.defaultQuality)
    }

    @Test
    fun `unknown audio output mode falls back without losing other settings`() {
        val root = Files.createTempDirectory("dmc-task-store-unknown-audio-output")
        val file = root.resolve("tasks.json")
        Files.writeString(file, JSONObject()
            .put("schemaVersion", 2)
            .put("tasks", org.json.JSONArray())
            .put("settings", JSONObject()
                .put("defaultDownloadDirectory", root.toString())
                .put("maxConcurrentDownloads", 4)
                .put("defaultQuality", "standard")
                .put("audioOutputMode", "EXCLUSIVE"))
            .toString())

        val settings = DownloadTaskStore(file).load().settings

        assertEquals(AudioOutputMode.SHARED, settings.audioOutputMode)
        assertEquals(4, settings.maxConcurrentDownloads)
        assertEquals("standard", settings.defaultQuality)
    }

    @Test
    fun `malformed task record is skipped while valid records load`() {
        val root = Files.createTempDirectory("hachimi-task-store")
        val path = root.resolve("tasks.json")
        val store = DownloadTaskStore(path)
        store.save(listOf(task()), DesktopSettings(defaultDownloadDirectory = root.toString()))
        val original = Files.readString(path)
        Files.writeString(path, original.replace(
            "\"tasks\":[",
            "\"tasks\":[{\"id\":\"broken\"},",
        ))

        assertEquals(listOf("task-1"), store.load().tasks.map { it.id })
    }

    @Test
    fun `corrupt document does not prevent startup and is preserved for diagnosis`() {
        val root = Files.createTempDirectory("hachimi-task-store")
        val path = root.resolve("tasks.json")
        Files.writeString(path, "{not valid json")

        val loaded = DownloadTaskStore(path).load()

        assertTrue(loaded.tasks.isEmpty())
        assertTrue(Files.exists(path) || Files.list(root).use { it.findAny().isPresent })
    }

    private fun task() = DownloadTask(
        id = "task-1",
        songId = 42,
        songName = "Song",
        artists = "Artist",
        album = "Album",
        requestedQuality = "exhigh",
        actualQuality = "exhigh",
        filePath = "C:\\Music\\Artist - Song [42].mp3",
        bytesDownloaded = 128,
        totalBytes = 1024,
        status = DownloadTaskStatus.PAUSED,
        createdAtMillis = 100,
        updatedAtMillis = 200,
        etag = "\"stable-etag\"",
        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
    )
}
