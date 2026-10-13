package com.qing.hachimi.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Chooses a safe image sidecar path from the configured media and cover directory rules. */
object ArtworkDestinationResolver {
    fun songCoverPath(
        song: SongHit,
        audioPath: Path?,
        settings: DesktopSettings,
        collectionName: String?,
        imageExtension: String,
    ): Path {
        val extension = requireImageExtension(imageExtension)
        val predictedAudioPath = audioPath?.toAbsolutePath()?.normalize()
            ?: FileNamePolicy.destination(
                outputDirectory(song, settings, collectionName),
                song,
                type = null,
                conflictPolicy = settings.fileConflictPolicy,
            )
        val stem = predictedAudioPath.fileName.toString().let { name ->
            name.lastIndexOf('.').takeIf { it > 0 }?.let { name.substring(0, it) } ?: name
        }
        val root = if (settings.coverDirectoryMode == CoverDirectoryMode.CUSTOM_DIRECTORY) {
            Path.of(settings.customCoverDirectory).toAbsolutePath().normalize()
        } else {
            predictedAudioPath.parent ?: Path.of(settings.defaultDownloadDirectory).toAbsolutePath().normalize()
        }
        return child(root, stem, extension)
    }

    fun albumCoverPath(
        albumName: String,
        artist: String,
        settings: DesktopSettings,
        collectionName: String?,
        imageExtension: String,
        audioPath: Path? = null,
    ): Path {
        val extension = requireImageExtension(imageExtension)
        val placeholder = SongHit(1L, albumName.ifBlank { "Unknown Album" }, artist, albumName)
        val root = if (settings.coverDirectoryMode == CoverDirectoryMode.CUSTOM_DIRECTORY) {
            Path.of(settings.customCoverDirectory).toAbsolutePath().normalize()
        } else {
            audioPath?.toAbsolutePath()?.normalize()?.parent
                ?: outputDirectory(placeholder, settings, collectionName)
        }
        val stem = FileNamePolicy.component(
            "${artist.ifBlank { "Unknown Artist" }} - ${albumName.ifBlank { "Unknown Album" }}",
        )
        return child(root, stem, extension)
    }

    private fun outputDirectory(song: SongHit, settings: DesktopSettings, collectionName: String?): Path =
        FileNamePolicy.groupedDirectory(
            Path.of(settings.defaultDownloadDirectory),
            settings.folderGrouping,
            collectionName.orEmpty(),
            song,
        )

    private fun requireImageExtension(extension: String): String = extension.lowercase().also {
        require(it in setOf(".jpg", ".png", ".webp")) { "Unsupported artwork extension" }
    }

    private fun child(root: Path, rawStem: String, extension: String): Path {
        val directory = root.toAbsolutePath().normalize()
        val safeStem = FileNamePolicy.component(rawStem)
        val available = MAX_PATH_CHARS - directory.toString().length - extension.length - 1
        require(available > 0) { "Cover directory path is too long" }
        val stem = safeStem.take(available).let { value ->
            if (value.lastOrNull()?.isHighSurrogate() == true) value.dropLast(1) else value
        }.trimEnd('.', ' ')
        require(stem.isNotBlank()) { "Cover filename is empty" }
        val target = directory.resolve(stem + extension).normalize()
        require(target.parent == directory) { "Cover path escaped its selected directory" }
        require(target.toString().length <= MAX_PATH_CHARS) { "Cover path is too long" }
        return target
    }

    private const val MAX_PATH_CHARS = 240
}

/** Writes a validated cover atomically, deduplicating identical bytes and never replacing a file. */
class ArtworkFileStore internal constructor(
    private val moveFile: (Path, Path) -> Unit = ::atomicCommit,
) {
    @Synchronized
    fun save(payload: ArtworkPayload, destination: Path): Path {
        val canonicalExtension = FileNamePolicy.imageExtension(payload.mimeType)
            ?: throw IllegalArgumentException("Unsupported artwork MIME type")
        require(payload.extension.equals(canonicalExtension, ignoreCase = true)) {
            "Artwork MIME type and extension do not match"
        }
        require(payload.bytes.isNotEmpty() && payload.bytes.size <= MAX_ARTWORK_BYTES) {
            "Artwork size is invalid"
        }
        require(artworkPayloadFromTrustedBytes(payload.bytes)?.mimeType == payload.mimeType) {
            "Artwork signature does not match its MIME type"
        }

        val requested = destination.toAbsolutePath().normalize()
        val parent = requested.parent ?: throw IllegalArgumentException("Cover destination has no parent")
        Files.createDirectories(parent)
        val realParent = parent.toRealPath()
        val requestedName = requested.fileName?.toString() ?: throw IllegalArgumentException("Cover filename is missing")
        val stem = requestedName.removeSuffix(".${requestedName.substringAfterLast('.', "")}")
        val extension = ".${requestedName.substringAfterLast('.', "")}".lowercase()
        require(extension == canonicalExtension) { "Cover destination extension does not match its MIME type" }
        require(stem.isNotBlank()) { "Cover filename is empty" }
        val base = realParent.resolve(requestedName).normalize()
        require(base.parent == realParent) { "Cover destination escaped its directory" }

        var index = 1
        while (index <= MAX_COLLISION_ATTEMPTS) {
            val candidateName = if (index == 1) requestedName else "$stem ($index)$extension"
            val candidate = realParent.resolve(candidateName).normalize()
            require(candidate.parent == realParent) { "Cover destination escaped its directory" }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    Files.size(candidate) == payload.bytes.size.toLong() &&
                    Files.readAllBytes(candidate).contentEquals(payload.bytes)
                ) return candidate
                index++
                continue
            }

            try {
                Files.createFile(candidate)
            } catch (_: FileAlreadyExistsException) {
                index++
                continue
            }
            var committed = false
            val temporary = try {
                Files.createTempFile(realParent, ".hachimi-cover-", ".tmp")
            } catch (error: Exception) {
                Files.deleteIfExists(candidate)
                throw error
            }
            try {
                Files.write(
                    temporary,
                    payload.bytes,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                moveFile(temporary, candidate)
                committed = true
                return candidate
            } finally {
                Files.deleteIfExists(temporary)
                if (!committed) Files.deleteIfExists(candidate)
            }
        }
        throw FileAlreadyExistsException("Unable to allocate a unique cover filename")
    }

    companion object {
        private const val MAX_COLLISION_ATTEMPTS = 10_000

        private fun atomicCommit(temporary: Path, target: Path) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
