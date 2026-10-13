package com.qing.hachimi.desktop.libmpv

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("native-mpv-poc")
class LibMpvNativePocTest {
    @Test
    fun `initializes the explicitly pinned native client API`() {
        val libraryPath = Path.of(requireNotNull(System.getProperty("dmc.libmpv.path")))
        val archivePath = Path.of(requireNotNull(System.getProperty("dmc.libmpv.archive")))
        assertTrue(libraryPath.isAbsolute, "libmpv path must be absolute")
        assertTrue(Files.isRegularFile(libraryPath), "explicit libmpv DLL does not exist")
        assertTrue(Files.isRegularFile(archivePath), "pinned mpv archive does not exist")

        val api = loadApi(libraryPath)
        val version = apiVersion(api.mpv_client_api_version())
        assertTrue(version.first >= 2, "unsupported mpv client API ${version.first}.${version.second}")
        println(
            "R1_NATIVE_ARTIFACT|commit=c3ac7f39514c7f45bc38a18e66887240539ee33c" +
                "|clientApi=${version.first}.${version.second}" +
                "|archiveSha256=${sha256(archivePath)}|dllSha256=${sha256(libraryPath)}",
        )

        LibMpvNativeDecoder(api).use { decoder ->
            println("R1_NATIVE_API_READY|clientApi=${version.first}.${version.second}|initialized=true")
        }
    }

    @Test
    fun `decodes the existing MP3 fixture and receives EOF`() = assertHealthyFixture("MP3", "synthetic-tone.mp3")

    @Test
    fun `decodes the existing short FLAC fixture and receives EOF`() {
        val result = decode(resource("audio-fixtures/synthetic-tone.flac"))
        printResult("short FLAC", result)
        assertTrue(result.isCompletedDecode, "short FLAC completion failed: ${result.summary()}")
    }

    @Test
    fun `decodes a longer FLAC fixture and observes position advancement`() =
        assertHealthyFixture("FLAC progress", "synthetic-progress.flac")

    @Test
    fun `decodes a generated PCM WAV fixture and receives EOF`(@TempDir tempDir: Path) {
        val wav = PcmWaveFixture.write(tempDir.resolve("generated tone.wav"), durationMillis = 900)
        assertHealthyDecode("WAV", decode(wav))
    }

    @Test
    fun `decodes the existing AAC M4A fixture and receives EOF`() =
        assertHealthyFixture("M4A/AAC", "synthetic-tagged-aac.m4a")

    @Test
    fun `loads audio through a unicode and space containing path`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("测试 音频目录").resolve("星の曲.flac")
        Files.createDirectories(path.parent)
        Files.copy(resource("audio-fixtures/synthetic-progress.flac"), path)
        assertHealthyDecode("Unicode FLAC", decode(path))
    }

    @Test
    fun `missing and damaged media are not reported as healthy decodes`(@TempDir tempDir: Path) {
        val missing = decode(tempDir.resolve("missing file.m4a"), Duration.ofSeconds(8))
        printResult("missing", missing)
        assertFalse(missing.isHealthyDecode, "missing file must not pass the healthy-decode criteria")

        val damaged = tempDir.resolve("damaged.m4a")
        Files.copy(resource("audio-fixtures/synthetic-damaged.m4a"), damaged)
        val corrupt = decode(damaged, Duration.ofSeconds(8))
        printResult("damaged M4A", corrupt)
        assertFalse(corrupt.isHealthyDecode, "damaged file must not pass the healthy-decode criteria")
    }

    @Test
    fun `stop end reason is distinct from natural EOF`() {
        val api = loadApi()
        LibMpvNativeDecoder(api).use { decoder ->
            val result = decoder.loadThenStop(resource("audio-fixtures/synthetic-tone.mp3"))
            printResult("stopped MP3", result)
            assertTrue(result.fileLoaded, result.summary())
            assertTrue((result.stopCommandStatus ?: -1) >= 0, result.summary())
            assertEquals(NativeEndReason.STOP, result.endReason, result.summary())
        }
    }

    @Test
    fun `repeated create load and destroy cycles finish without leaked handles`(@TempDir tempDir: Path) {
        val wave = PcmWaveFixture.write(tempDir.resolve("cycle.wav"), durationMillis = 700)
        repeat(3) { cycle ->
            val result = decode(wave)
            assertHealthyDecode("lifecycle-${cycle + 1}", result)
        }
    }

    private fun assertHealthyFixture(label: String, name: String) =
        assertHealthyDecode(label, decode(resource("audio-fixtures/$name")))

    private fun assertHealthyDecode(label: String, result: NativeDecodeResult) {
        printResult(label, result)
        assertTrue(result.isHealthyDecode, "$label failed healthy decode criteria: ${result.summary()}")
    }

    private fun printResult(label: String, result: NativeDecodeResult) {
        println("R1_DECODE|format=$label|${result.summary()}")
    }

    private fun decode(path: Path, timeout: Duration = Duration.ofSeconds(30)): NativeDecodeResult =
        LibMpvNativeDecoder(loadApi()).use { it.decodeToEnd(path, timeout) }

    private fun loadApi(path: Path = Path.of(requireNotNull(System.getProperty("dmc.libmpv.path")))): LibMpvNativeApi =
        Native.load(
            path.toAbsolutePath().normalize().toString(),
            LibMpvNativeApi::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
        )

    private fun resource(name: String): Path =
        Path.of(requireNotNull(javaClass.classLoader.getResource(name)) { "test fixture missing: $name" }.toURI())

    private fun apiVersion(raw: Int): Pair<Int, Int> {
        val unsigned = raw.toLong() and 0xffff_ffffL
        return (unsigned ushr 16).toInt() to (unsigned and 0xffff).toInt()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
