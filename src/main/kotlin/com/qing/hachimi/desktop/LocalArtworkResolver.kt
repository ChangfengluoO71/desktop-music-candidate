package com.qing.hachimi.desktop

import org.jaudiotagger.audio.AudioFileIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Reads embedded or neighboring local artwork only; it never contacts a network source. */
class LocalArtworkResolver(
    private val embeddedArtworkReader: (Path) -> ByteArray? = ::readEmbeddedArtwork,
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
) {
    fun resolve(audioPath: Path): ArtworkPayload? {
        runCatching { embeddedArtworkReader(audioPath) }
            .getOrNull()
            ?.let(::validatedPayload)
            ?.let { return it }

        val parent = audioPath.parent ?: return null
        val stem = audioPath.fileName?.toString()?.substringBeforeLast('.', audioPath.fileName.toString()).orEmpty()
        val names = setOf("cover", "folder", "front", stem).filter(String::isNotBlank)
        val candidates = runCatching {
            Files.list(parent).use { files ->
                files.filter { path ->
                    val name = path.fileName.toString()
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    name.substringBeforeLast('.', "").lowercase(Locale.ROOT) in names.map { it.lowercase(Locale.ROOT) } &&
                        extension in SUPPORTED_EXTENSIONS && Files.isRegularFile(path)
                }.sorted().toList()
            }
        }.getOrDefault(emptyList())
        return candidates.firstNotNullOfOrNull { path ->
            runCatching {
                if (Files.size(path) !in 1..maxImageBytes) return@runCatching null
                validatedPayload(Files.readAllBytes(path))
            }.getOrNull()
        }
    }

    private fun validatedPayload(bytes: ByteArray): ArtworkPayload? =
        if (bytes.size.toLong() !in 1..maxImageBytes) null else artworkPayloadFromTrustedBytes(bytes)

    private companion object {
        const val DEFAULT_MAX_IMAGE_BYTES = 20L * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun readEmbeddedArtwork(path: Path): ByteArray? = runCatching {
            AudioFileIO.read(path.toFile()).tag?.artworkList?.firstOrNull()?.binaryData
        }.getOrNull()
    }
}
