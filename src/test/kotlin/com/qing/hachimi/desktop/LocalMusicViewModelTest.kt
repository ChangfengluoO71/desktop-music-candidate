package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMusicViewModelTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `mock candidate source flows through preview without downloading or replacing files`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("candidate"))
        val localFile = root.resolve("Song.mp3")
        Files.write(localFile, byteArrayOf(1, 2, 3, 4))
        val store = LocalMusicStore(temp.resolve("library.db"))
        val metadataReader = LocalAudioMetadataReader {
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 180.0, 128, 44_100, 16,
                "Song", listOf("Artist"), "Album", "Artist", 1, 1)
        }
        val original = Files.readAllBytes(localFile)
        val fakeSource = LocalUpgradeCandidateSource { track ->
            listOf(
                UpgradeCandidateRecord(
                    songId = 1234L,
                    title = track.title,
                    artists = track.artists,
                    durationSeconds = track.durationSeconds,
                    quality = track.audioQuality.copy(bitrateKbps = 320),
                ),
            )
        }
        val viewModel = LocalMusicViewModel(
            store = store,
            scanner = DesktopMusicScanner(store, metadataReader),
            candidateSource = fakeSource,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.addRoots(listOf(root))
            withTimeout(5_000) { viewModel.state.first { it.roots.size == 1 } }
            viewModel.scan()
            withTimeout(5_000) { viewModel.state.first { !it.isScanning && it.tracks.size == 1 } }
            viewModel.previewUpgrades()
            val state = withTimeout(5_000) { viewModel.state.first { it.upgradeCandidates.isNotEmpty() } }
            assertEquals(1234L, state.upgradeCandidates.single().candidateSongId)
            assertEquals(IdentityStatus.POSSIBLE_MATCH, state.upgradeCandidates.single().identityStatus)
            assertEquals(QualityParameterComparison.HIGHER_PARAMETERS, state.upgradeCandidates.single().qualityComparison)
            assertEquals(original.toList(), Files.readAllBytes(localFile).toList())
        } finally {
            viewModel.shutdown()
        }
    }

    @Test
    fun `filter state remains responsive while scan runs in background`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("responsive"))
        repeat(60) { index -> Files.write(root.resolve("track-$index.mp3"), byteArrayOf(1, 2, 3)) }
        val store = LocalMusicStore(temp.resolve("responsive.db"))
        val slowReader = LocalAudioMetadataReader {
            Thread.sleep(20)
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 1.0, 128, 44_100, 16,
                "Song", listOf("Artist"), "Album", "Artist", null, null)
        }
        val viewModel = LocalMusicViewModel(
            store,
            scanner = DesktopMusicScanner(store, slowReader),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.addRoots(listOf(root))
            withTimeout(5_000) { viewModel.state.first { it.roots.size == 1 } }
            viewModel.scan()
            withTimeout(5_000) { viewModel.state.first { it.isScanning } }
            viewModel.setArtistFilter("Artist")
            assertEquals("Artist", viewModel.state.value.artistFilter)
            assertTrue(viewModel.state.value.isScanning)
            viewModel.cancelScan()
            withTimeout(5_000) { viewModel.state.first { !it.isScanning } }
        } finally {
            viewModel.shutdown()
        }
    }
}
