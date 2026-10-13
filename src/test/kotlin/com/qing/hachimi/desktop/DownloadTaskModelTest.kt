package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadTaskModelTest {
    @Test
    fun `state machine accepts queue to resolve to download to complete`() {
        val queued = task()
        val resolving = DownloadTaskStateMachine.transition(
            queued,
            DownloadTaskStatus.RESOLVING,
            nowMillis = 200,
        )
        val downloading = DownloadTaskStateMachine.transition(
            resolving,
            DownloadTaskStatus.DOWNLOADING,
            nowMillis = 300,
        )
        val completed = DownloadTaskStateMachine.transition(
            downloading,
            DownloadTaskStatus.COMPLETED,
            nowMillis = 400,
        )

        assertEquals(DownloadTaskStatus.COMPLETED, completed.status)
        assertEquals(400, completed.updatedAtMillis)
        assertEquals(null, completed.errorSummary)
    }

    @Test
    fun `state machine rejects illegal transitions`() {
        assertFailsWith<IllegalStateException> {
            DownloadTaskStateMachine.transition(
                task(DownloadTaskStatus.COMPLETED),
                DownloadTaskStatus.DOWNLOADING,
                nowMillis = 200,
            )
        }
    }

    @Test
    fun `translation romanization and word lyrics independently request post processing`() {
        assertEquals(true, AudioPostProcessingOptions(saveTranslatedLyrics = true).requested)
        assertEquals(true, AudioPostProcessingOptions(saveRomanizedLyrics = true).requested)
        assertEquals(true, AudioPostProcessingOptions(saveWordLevelLyrics = true).requested)
        assertEquals(false, AudioPostProcessingOptions().requested)
    }

    private fun task(status: DownloadTaskStatus = DownloadTaskStatus.QUEUED) = DownloadTask(
        id = "task-1",
        songId = 42,
        songName = "Song",
        artists = "Artist",
        album = "Album",
        requestedQuality = "exhigh",
        filePath = "C:\\Music\\Artist - Song [42].audio",
        status = status,
        createdAtMillis = 100,
        updatedAtMillis = 100,
    )
}
