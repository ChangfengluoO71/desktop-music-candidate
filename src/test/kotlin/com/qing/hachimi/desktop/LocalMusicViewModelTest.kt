package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMusicViewModelTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `browse mode sort and folder filters update library state`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("browse"))
        Files.createDirectories(root.resolve("Jazz"))
        Files.write(root.resolve("Jazz/b.mp3"), byteArrayOf(1))
        Files.write(root.resolve("a.mp3"), byteArrayOf(1))
        val store = LocalMusicStore(temp.resolve("browse.db"))
        val scanner = DesktopMusicScanner(store, LocalAudioMetadataReader { path ->
            val isBeta = path.fileName.toString().startsWith("b")
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 1.0, 128, 44_100, 16,
                if (isBeta) "Beta" else "Alpha", listOf(if (isBeta) "Artist B" else "Artist A"),
                if (isBeta) "Album Z" else "Album A", "", null, null)
        })
        val viewModel = LocalMusicViewModel(store, scanner, mainDispatcher = Dispatchers.Default)
        try {
            viewModel.addRoots(listOf(root))
            withTimeout(3_000) { viewModel.state.first { it.roots.size == 1 } }
            viewModel.scan()
            withTimeout(3_000) { viewModel.state.first { !it.isScanning && it.tracks.size == 2 } }
            viewModel.setBrowseMode(LocalMusicBrowseMode.SONGS)
            viewModel.setSortMode(LocalMusicSortMode.ARTIST)
            viewModel.setFolderFilter(root.resolve("Jazz").toString())
            withTimeout(3_000) { viewModel.state.first { it.tracks.size == 2 } }
            val state = viewModel.state.value
            assertEquals(LocalMusicBrowseMode.SONGS, state.browseMode)
            assertEquals(LocalMusicSortMode.ARTIST, state.sortMode)
            val trackPaths = state.tracks.joinToString { track ->
                "${track.title}: ${track.filePath} -> ${normalizedLibraryPath(track.filePath)}"
            }
            val rootPaths = state.roots.joinToString { rootPath ->
                "${rootPath.path} -> ${normalizedLibraryPath(rootPath.path)}"
            }
            assertEquals(
                listOf("Beta"),
                state.visibleTracks.map(LocalTrack::title),
                "folderFilter=${state.folderFilter}; normalizedFolder=${state.folderFilter?.let(::normalizedLibraryPath)}; " +
                    "tracks=$trackPaths; roots=$rootPaths",
            )
        } finally {
            viewModel.shutdown()
        }
    }

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

    @Test
    fun `opt in directory watcher refreshes local index after file changes`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("watched-music"))
        val store = LocalMusicStore(temp.resolve("watched.db"))
        val reader = LocalAudioMetadataReader {
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 10.0, 128, 44_100, 16, "Watched Song", listOf("Artist"), "Album", "Artist", null, null)
        }
        val viewModel = LocalMusicViewModel(
            store = store,
            scanner = DesktopMusicScanner(store, reader),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.addRoots(listOf(root))
            val registered = withTimeout(3_000) { viewModel.state.first { it.roots.size == 1 }.roots.single() }
            viewModel.setRootAutoWatch(registered.path, true)
            withTimeout(3_000) { viewModel.state.first { it.roots.single().autoWatch } }

            Files.write(root.resolve("watched.mp3"), byteArrayOf(1, 2, 3))

            val updated = withTimeoutOrNull(5_000) { viewModel.state.first { it.tracks.any { track -> track.title == "Watched Song" } } }
            assertTrue(updated != null, "watch refresh timed out; message=${viewModel.state.value.message}, roots=${viewModel.state.value.roots}, tracks=${viewModel.state.value.tracks.size}")
            assertEquals(1, updated.tracks.size)
        } finally {
            viewModel.shutdown()
        }
    }

    @Test
    fun `watcher changes during full scan are applied after scan without losing index rows`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("scan-watch-race"))
        repeat(20) { index -> Files.write(root.resolve("track-$index.mp3"), byteArrayOf(1, 2, 3)) }
        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val store = LocalMusicStore(temp.resolve("scan-watch-race.db"))
        val reader = LocalAudioMetadataReader {
            readerStarted.countDown()
            releaseReader.await(3, TimeUnit.SECONDS)
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 10.0, 128, 44_100, 16, "Song", listOf("Artist"), "Album", "Artist", null, null)
        }
        val viewModel = LocalMusicViewModel(
            store = store,
            scanner = DesktopMusicScanner(store, reader),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.addRoots(listOf(root))
            val registered = withTimeout(3_000) { viewModel.state.first { it.roots.size == 1 }.roots.single() }
            viewModel.setRootAutoWatch(registered.path, true)
            withTimeout(3_000) { viewModel.state.first { it.roots.single().autoWatch } }

            viewModel.scan()
            assertTrue(readerStarted.await(3, TimeUnit.SECONDS), "full scan did not begin reading metadata")
            Files.write(root.resolve("late-added.mp3"), byteArrayOf(4, 5, 6))
            releaseReader.countDown()

            val recovered = withTimeout(10_000) {
                viewModel.state.first { state -> state.tracks.any { it.filePath.endsWith("late-added.mp3") } }
            }
            assertEquals(21, recovered.tracks.size)
        } finally {
            releaseReader.countDown()
            viewModel.shutdown()
        }
    }

}
