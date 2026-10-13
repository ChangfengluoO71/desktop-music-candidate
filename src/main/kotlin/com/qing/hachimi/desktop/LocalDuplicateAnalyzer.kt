package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Hashes files with a bounded buffer; this operation is explicit because it reads every file fully. */
class LocalDuplicateAnalyzer(
    private val store: LocalMusicStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun analyze(
        tracks: List<LocalTrack>,
        onProgress: (completed: Int, total: Int, currentPath: String?) -> Unit = { _, _, _ -> },
    ): DuplicateAnalysisResult = withContext(ioDispatcher) {
        val context = currentCoroutineContext()
        val hashes = linkedMapOf<String, MutableList<LocalTrack>>()
        var completed = 0
        var failed = 0
        tracks.forEach { track ->
            context.ensureActive()
            val path = Path.of(track.filePath)
            try {
                val before = Files.readAttributes(path, BasicFileAttributes::class.java)
                if (!before.isRegularFile || before.size() != track.fileSize ||
                    before.lastModifiedTime().toMillis() != track.lastModifiedMillis
                ) {
                    failed++
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(HASH_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val after = Files.readAttributes(path, BasicFileAttributes::class.java)
                    if (after.size() != before.size() || after.lastModifiedTime() != before.lastModifiedTime()) {
                        failed++
                    } else {
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (store.updateContentHash(track, hash)) {
                            hashes.getOrPut(hash) { mutableListOf() } += track.copy(contentSha256 = hash)
                        } else {
                            failed++
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            } finally {
                completed++
                onProgress(completed, tracks.size, track.filePath)
            }
        }
        val exact = hashes.entries.filter { it.value.size > 1 }
            .map { (hash, matches) -> ExactDuplicateGroup(hash, matches.toList()) }
        DuplicateAnalysisResult(
            exactDuplicates = exact,
            similarCandidates = LocalMusicAnalyzer.analyzeSimilarDuplicates(tracks),
            hashedFiles = hashes.values.sumOf { it.size },
            failedHashes = failed,
        )
    }

    companion object {
        private const val HASH_BUFFER_SIZE = 64 * 1024
    }
}
