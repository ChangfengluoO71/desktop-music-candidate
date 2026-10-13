package com.qing.hachimi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Test-only local-library window. It has no network client, downloader, or credential store. */
fun main() = application {
    val dataDirectory = remember {
        Path.of(
            System.getProperty("hachimi.localmusic.acceptance.dir")
                ?: Path.of(System.getProperty("java.io.tmpdir"), "DesktopMusicCandidateLocalMusicAcceptance").toString(),
        ).toAbsolutePath().normalize().also(Files::createDirectories)
    }
    val fixtureRoot = remember(dataDirectory) { prepareSyntheticLibrary(dataDirectory) }
    val fixtureSnapshot = remember(fixtureRoot) { snapshotAudioFiles(fixtureRoot) }
    val viewModel = remember(dataDirectory) {
        LocalMusicViewModel.createDefault(dataDirectory).also { vm -> vm.addRoots(listOf(fixtureRoot)) }
    }
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(fixtureRoot) {
        fixtureSnapshot.forEach { (path, file) ->
            println("LOCAL_MUSIC_FILE_BEFORE=$path|${file.first}|${file.second}")
        }
    }
    DisposableEffect(fixtureRoot) {
        onDispose {
            val after = snapshotAudioFiles(fixtureRoot)
            after.forEach { (path, file) ->
                println("LOCAL_MUSIC_FILE_AFTER=$path|${file.first}|${file.second}")
            }
            println("LOCAL_MUSIC_SOURCE_BYTES_UNCHANGED=${fixtureSnapshot == after}")
        }
    }
    LaunchedEffect(viewModel) {
        while (viewModel.state.value.roots.isEmpty()) delay(20)
        viewModel.scan()
    }
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch {
                    viewModel.shutdown()
                    exitApplication()
                }
            }
        },
        title = "Desktop Music Candidate — LOCAL MUSIC GUI ACCEPTANCE",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        resizable = true,
    ) {
        MiuixTheme(controller = controller) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("仅含合成音频的本地验收窗口；不访问网易云，不读取账号凭据。")
                Box(Modifier.weight(1f)) { LocalMusicScreen(viewModel) }
            }
        }
    }
}

private fun prepareSyntheticLibrary(dataDirectory: Path): Path {
    val root = dataDirectory.resolve("synthetic-music")
    Files.createDirectories(root)
    copyResourceIfMissing("/audio-fixtures/synthetic-tone.mp3", root.resolve("Tone copy A.mp3"))
    if (!Files.exists(root.resolve("Tone copy B.mp3"))) {
        Files.copy(root.resolve("Tone copy A.mp3"), root.resolve("Tone copy B.mp3"))
    }
    copyResourceIfMissing("/audio-fixtures/synthetic-tone.flac", root.resolve("Tone FLAC.flac"))
    copyResourceIfMissing("/audio-fixtures/synthetic-tagged-aac.m4a", root.resolve("Tagged AAC Fixture.m4a"))
    copyResourceIfMissing("/audio-fixtures/synthetic-damaged.m4a", root.resolve("Damaged M4A Fixture.m4a"))
    val wave = root.resolve("Tone WAV.wav")
    if (!Files.exists(wave)) createWave(wave)
    return root
}

private fun snapshotAudioFiles(root: Path): Map<String, Pair<Long, String>> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in
        setOf("mp3", "flac", "wav", "m4a")
    }.sorted().toList().associate { path ->
        val relative = root.relativize(path).toString().replace('\\', '/')
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        relative to (Files.size(path) to digest)
    }
}

private fun copyResourceIfMissing(resource: String, target: Path) {
    if (Files.exists(target)) return
    LocalMusicGuiAcceptanceMainMarker::class.java.getResourceAsStream(resource)!!.use { input -> Files.copy(input, target) }
}

private object LocalMusicGuiAcceptanceMainMarker

private fun createWave(path: Path) {
    val sampleRate = 44_100
    val sampleCount = sampleRate / 2
    val dataSize = sampleCount * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    buffer.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray()).putInt(dataSize)
    repeat(sampleCount) { buffer.putShort(0) }
    Files.write(path, buffer.array())
}
