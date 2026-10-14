package com.qing.hachimi.desktop

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun interface ArtworkThumbnailDecoder {
    fun decode(bytes: ByteArray, targetEdgePx: Int): ImageBitmap?
}

/** Decodes only validated artwork and bounds source pixels and display bitmap dimensions. */
internal object SafeArtworkThumbnailDecoder : ArtworkThumbnailDecoder {
    override fun decode(bytes: ByteArray, targetEdgePx: Int): ImageBitmap? {
        val payload = artworkPayloadFromTrustedBytes(bytes) ?: return null
        val target = targetEdgePx.coerceIn(1, MAX_TARGET_EDGE)
        return runCatching {
            ByteArrayInputStream(payload.bytes).use { inputBytes ->
                ImageIO.createImageInputStream(inputBytes).use { input ->
                    if (input != null) {
                        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                        if (reader != null) {
                            decodeWithImageReader(reader, input, target)
                        } else if (payload.mimeType == "image/webp") {
                            decodeWebp(payload.bytes, target)
                        } else {
                            null
                        }
                    } else if (payload.mimeType == "image/webp") {
                        decodeWebp(payload.bytes, target)
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()
    }

    private fun decodeWithImageReader(reader: ImageReader, input: javax.imageio.stream.ImageInputStream, target: Int): ImageBitmap? {
        try {
            val imageReader = reader
            imageReader.setInput(input, true, true)
            val width = imageReader.getWidth(0)
            val height = imageReader.getHeight(0)
            if (!safeDimensions(width, height, MAX_SUBSAMPLED_SOURCE_PIXELS)) return null
            val sample = ceil(maxOf(width, height).toDouble() / target).roundToInt().coerceAtLeast(1)
            val params = imageReader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val decoded = imageReader.read(0, params) ?: return null
            return decoded.toThumbnail(target).toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }

    private fun decodeWebp(bytes: ByteArray, target: Int): ImageBitmap? {
        val dimensions = webpDimensions(bytes) ?: return null
        if (!safeDimensions(dimensions.first, dimensions.second, MAX_FULL_DECODE_PIXELS)) return null
        val source = bytes.decodeToImageBitmap()
        if (source.width != dimensions.first || source.height != dimensions.second) return null
        return source.toThumbnail(target)
    }

    private fun safeDimensions(width: Int, height: Int, maxPixels: Long): Boolean =
        width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION &&
            width.toLong() * height.toLong() <= maxPixels

    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF" ||
            bytes.copyOfRange(8, 12).decodeToString() != "WEBP"
        ) return null
        fun byte(index: Int) = bytes[index].toInt() and 0xff
        fun u24le(index: Int) = byte(index) or (byte(index + 1) shl 8) or (byte(index + 2) shl 16)
        return when (bytes.copyOfRange(12, 16).decodeToString()) {
            "VP8X" -> (1 + u24le(24)) to (1 + u24le(27))
            "VP8L" -> {
                if (bytes.size < 25 || byte(20) != 0x2f) null
                else {
                    val width = 1 + (byte(21) and 0x3f) + ((byte(22) and 0x03) shl 6)
                    val height = 1 + ((byte(22) and 0xfc) shr 2) + ((byte(23) and 0x0f) shl 6)
                    width to height
                }
            }
            "VP8 " -> {
                if (bytes.size < 30 || byte(23) != 0x9d || byte(24) != 0x01 || byte(25) != 0x2a) null
                else {
                    val width = (byte(26) or (byte(27) shl 8)) and 0x3fff
                    val height = (byte(28) or (byte(29) shl 8)) and 0x3fff
                    width to height
                }
            }
            else -> null
        }
    }

    private fun BufferedImage.toThumbnail(target: Int): BufferedImage {
        val factor = minOf(1.0, target.toDouble() / maxOf(width, height))
        val outWidth = (width * factor).roundToInt().coerceAtLeast(1)
        val outHeight = (height * factor).roundToInt().coerceAtLeast(1)
        if (outWidth == width && outHeight == height) return this
        return BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB).also { output ->
            val graphics = output.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(this, 0, 0, outWidth, outHeight, null)
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun ImageBitmap.toThumbnail(target: Int): ImageBitmap {
        val factor = minOf(1.0, target.toDouble() / maxOf(width, height))
        val outWidth = (width * factor).roundToInt().coerceAtLeast(1)
        val outHeight = (height * factor).roundToInt().coerceAtLeast(1)
        if (outWidth == width && outHeight == height) return this
        val output = ImageBitmap(outWidth, outHeight)
        val canvas = Canvas(output)
        val paint = Paint().apply { filterQuality = FilterQuality.High }
        canvas.drawImageRect(
            image = this,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(width, height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(outWidth, outHeight),
            paint = paint,
        )
        return output
    }

    private const val MAX_TARGET_EDGE = 1_024
    private const val MAX_SOURCE_DIMENSION = 32_768
    private const val MAX_SUBSAMPLED_SOURCE_PIXELS = 250_000_000L
    private const val MAX_FULL_DECODE_PIXELS = 16_000_000L
}

/** Shares extracted covers across the local library and player, with bounded decoded-image memory. */
internal class LocalArtworkRepository(
    private val resolver: LocalArtworkResolver = LocalArtworkResolver(),
    private val decoder: ArtworkThumbnailDecoder = SafeArtworkThumbnailDecoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxDecodedBytes: Long = DEFAULT_MAX_DECODED_BYTES,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LinkedHashMap<Key, CacheEntry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<Key, CompletableDeferred<ImageBitmap?>>()
    private val directoryGenerations = mutableMapOf<String, Long>()
    private var epoch = 0L
    private var cachedBytes = 0L

    init {
        require(maxDecodedBytes >= 0L)
    }

    suspend fun thumbnail(track: LocalTrack, targetEdgePx: Int): ImageBitmap? {
        val edge = targetEdgePx.coerceIn(1, 1_024)
        val key = createKey(track.filePath, edge) ?: return null
        var launchDecode = false
        val deferred = synchronized(lock) {
            cache[key]?.let { return it.bitmap }
            inFlight[key] ?: CompletableDeferred<ImageBitmap?>().also {
                inFlight[key] = it
                launchDecode = true
            }
        }
        if (launchDecode) {
            scope.launch {
                try {
                    val path = Path.of(key.sourcePath)
                    val payload = withContext(ioDispatcher) { resolver.resolve(path) }
                    val image = payload?.let { withContext(ioDispatcher) { decoder.decode(it.bytes, edge) } }
                    synchronized(lock) {
                        inFlight.remove(key)
                        if (image != null && isCurrent(key)) addToCache(key, image)
                    }
                    deferred.complete(image)
                } catch (cancelled: CancellationException) {
                    synchronized(lock) { inFlight.remove(key) }
                    deferred.cancel(cancelled)
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) { inFlight.remove(key) }
                    deferred.complete(null)
                }
            }
        }
        return deferred.await()
    }

    /** Invalidating one path invalidates nearby tracks too, covering shared folder/front images. */
    fun invalidate(path: Path) {
        val normalized = (normalizePath(path) ?: path.toAbsolutePath().normalize().toString()).lowercase(Locale.ROOT)
        val parent = Path.of(normalized).parent?.toString()?.lowercase(Locale.ROOT)
        synchronized(lock) {
            if (parent != null) directoryGenerations[parent] = (directoryGenerations[parent] ?: 0L) + 1L
            val stale = cache.keys.filter { it.path == normalized || (parent != null && it.parent == parent) }
            stale.forEach(::removeCached)
        }
    }

    fun clear() {
        synchronized(lock) {
            epoch++
            cache.clear()
            cachedBytes = 0L
        }
    }

    internal fun cachedDecodedBytes(): Long = synchronized(lock) { cachedBytes }

    override fun close() {
        clear()
        scope.cancel()
    }

    private fun createKey(rawPath: String, edge: Int): Key? = runCatching {
        val path = Path.of(rawPath)
        if (!Files.isRegularFile(path)) return null
        val canonical = normalizePath(path) ?: return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        val pathKey = canonical.lowercase(Locale.ROOT)
        val parent = Path.of(pathKey).parent?.toString()?.lowercase(Locale.ROOT).orEmpty()
        synchronized(lock) {
            Key(pathKey, canonical, parent, attributes.size(), attributes.lastModifiedTime().toMillis(), edge, epoch, directoryGenerations[parent] ?: 0L)
        }
    }.getOrNull()

    private fun normalizePath(path: Path): String? = runCatching {
        val absolute = path.toAbsolutePath().normalize()
        (if (Files.exists(absolute)) absolute.toRealPath() else absolute).toString()
    }.getOrNull()

    private fun isCurrent(key: Key): Boolean {
        val current = createKey(key.sourcePath, key.edge) ?: return false
        return current.size == key.size && current.modifiedMillis == key.modifiedMillis &&
            current.epoch == key.epoch && current.directoryGeneration == key.directoryGeneration
    }

    private fun addToCache(key: Key, image: ImageBitmap) {
        val size = image.width.toLong() * image.height.toLong() * BYTES_PER_PIXEL
        if (size <= 0L || size > maxDecodedBytes) return
        cache.remove(key)?.let { cachedBytes -= it.sizeBytes }
        cache[key] = CacheEntry(image, size)
        cachedBytes += size
        val iterator = cache.entries.iterator()
        while (cachedBytes > maxDecodedBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            cachedBytes -= eldest.value.sizeBytes
            iterator.remove()
        }
    }

    private fun removeCached(key: Key) {
        cache.remove(key)?.let { cachedBytes -= it.sizeBytes }
    }

    private data class Key(
        val path: String,
        val sourcePath: String,
        val parent: String,
        val size: Long,
        val modifiedMillis: Long,
        val edge: Int,
        val epoch: Long,
        val directoryGeneration: Long,
    )

    private data class CacheEntry(val bitmap: ImageBitmap, val sizeBytes: Long)

    companion object {
        const val DEFAULT_MAX_DECODED_BYTES = 64L * 1024L * 1024L
        private const val BYTES_PER_PIXEL = 4L
    }
}
