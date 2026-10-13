package com.qing.hachimi.desktop.libmpv

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin

internal object PcmWaveFixture {
    fun write(path: Path, durationMillis: Int = 1_000, sampleRate: Int = 44_100): Path {
        require(durationMillis > 0)
        require(sampleRate > 0)
        val channels = 1
        val bitsPerSample = 16
        val sampleCount = (sampleRate.toLong() * durationMillis / 1_000).toInt()
        val dataBytes = sampleCount * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        repeat(sampleCount) { index ->
            val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * Short.MAX_VALUE * 0.15).toInt()
            buffer.putShort(sample.toShort())
        }
        Files.createDirectories(path.parent)
        return Files.write(path, buffer.array())
    }
}
