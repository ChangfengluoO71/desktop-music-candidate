package com.qing.hachimi.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

enum class ImmersiveBackdropSource { ALBUM_ART, CUSTOM_IMAGE, SOLID_COLOR }

enum class ImmersiveBackdropMaterial { SOFT_FOCUS, ACRYLIC, ALBUM_GRADIENT }

internal suspend fun loadCustomBackdrop(pathText: String, targetEdgePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val path = Path.of(pathText).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) return@runCatching null
        val size = Files.size(path)
        if (size !in 1..MAX_CUSTOM_BACKDROP_BYTES) return@runCatching null
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_CUSTOM_BACKDROP_BYTES.toInt() + 1) }
        if (bytes.size !in 1..MAX_CUSTOM_BACKDROP_BYTES.toInt()) return@runCatching null
        SafeArtworkThumbnailDecoder.decode(bytes, targetEdgePx.coerceIn(1, MAX_CUSTOM_BACKDROP_EDGE))
    }.getOrNull()
}

internal suspend fun extractBackdropPalette(image: ImageBitmap): List<Color> = withContext(Dispatchers.Default) {
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    val buckets = HashMap<Int, PaletteBucket>()
    val stride = maxOf(1, maxOf(image.width, image.height) / 48)
    for (y in 0 until image.height step stride) {
        for (x in 0 until image.width step stride) {
            val argb = pixels[y * image.width + x]
            val alpha = argb ushr 24 and 0xff
            val red = argb ushr 16 and 0xff
            val green = argb ushr 8 and 0xff
            val blue = argb and 0xff
            if (alpha < 128) continue
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            if (max - min < 18 || max < 32 || min > 244) continue
            val key = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
            buckets.getOrPut(key) { PaletteBucket() }.add(red, green, blue)
        }
    }
    val colors = buckets.values.sortedByDescending { it.count }.take(3).map { it.toColor() }
    colors.ifEmpty { listOf(Color(0xFF183444), Color(0xFF352B59), Color(0xFF171A22)) }
}

internal fun parseBackdropColor(value: String): Color? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it.digitToIntOrNull(16) == null }) return null
    return runCatching { Color(0xFF000000.toInt() or normalized.toLong(16).toInt()) }.getOrNull()
}

private class PaletteBucket {
    var count = 0
        private set
    private var redTotal = 0L
    private var greenTotal = 0L
    private var blueTotal = 0L

    fun add(red: Int, green: Int, blue: Int) {
        count++
        redTotal += red
        greenTotal += green
        blueTotal += blue
    }

    fun toColor() = Color(
        red = ((redTotal / count).toInt() / 255f).coerceIn(0.08f, 0.88f),
        green = ((greenTotal / count).toInt() / 255f).coerceIn(0.08f, 0.88f),
        blue = ((blueTotal / count).toInt() / 255f).coerceIn(0.08f, 0.88f),
    )
}

private const val MAX_CUSTOM_BACKDROP_BYTES = 32L * 1024L * 1024L
private const val MAX_CUSTOM_BACKDROP_EDGE = 1_024
