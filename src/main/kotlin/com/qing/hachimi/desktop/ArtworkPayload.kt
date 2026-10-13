package com.qing.hachimi.desktop

/** Validated image bytes and their media type, held in memory only. */
data class ArtworkPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

internal fun artworkPayloadFromTrustedBytes(bytes: ByteArray): ArtworkPayload? {
    val mime = when {
        bytes.startsWithBytes(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) -> "image/jpeg"
        bytes.startsWithBytes(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> return null
    }
    val extension = FileNamePolicy.imageExtension(mime) ?: return null
    return ArtworkPayload(bytes.copyOf(), mime, extension)
}

private fun ByteArray.startsWithBytes(vararg expected: Byte): Boolean =
    size >= expected.size && expected.indices.all { this[it] == expected[it] }
