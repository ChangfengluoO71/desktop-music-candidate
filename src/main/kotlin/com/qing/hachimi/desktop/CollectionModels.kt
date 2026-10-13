package com.qing.hachimi.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class CollectionSourceType { PLAYLIST, ALBUM }

data class CollectionReference(val type: CollectionSourceType, val id: Long) {
    init { require(id > 0L) }
}

object NeteaseCollectionLinkParser {
    private val acceptedHosts = setOf("music.163.com")

    fun parse(raw: String): CollectionReference? = runCatching {
        val uri = URI(raw.trim())
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host?.lowercase() !in acceptedHosts || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port !in setOf(80, 443)) return null

        val actual = uri.rawPath.orEmpty() to uri.rawQuery.orEmpty()
        val fragment = uri.rawFragment.orEmpty().removePrefix("/")
        val (path, query) = if (fragment.isNotBlank()) {
            fragment.substringBefore('?') to fragment.substringAfter('?', "")
        } else actual
        val type = when (path.trim('/').substringBefore('/')) {
            "playlist" -> CollectionSourceType.PLAYLIST
            "album" -> CollectionSourceType.ALBUM
            else -> return null
        }
        val values = query.split('&').mapNotNull { token ->
            if (token.isBlank()) null else {
                val key = URLDecoder.decode(token.substringBefore('='), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(token.substringAfter('=', ""), StandardCharsets.UTF_8)
                key to value
            }
        }.filter { it.first == "id" }.map { it.second }
        if (values.size != 1) return null
        val id = values.single().toLongOrNull()?.takeIf { it > 0L } ?: return null
        CollectionReference(type, id)
    }.getOrNull()
}

data class CollectionDescriptor(
    val reference: CollectionReference,
    val name: String,
    val creator: String = "",
    val coverUrl: String = "",
    val trackCount: Int,
    val trackIds: List<Long?>,
    val embeddedSongs: List<SongHit> = emptyList(),
    val description: String = "",
) {
    init {
        require(trackCount >= 0)
        require(trackIds.filterNotNull().all { it > 0L })
    }
}

enum class CollectionFailureKind { UNAUTHORIZED, NETWORK, MALFORMED, NOT_FOUND }

class CollectionApiException(
    val kind: CollectionFailureKind,
    message: String = "集合内容暂时无法读取",
) : Exception(message)

interface CollectionApi {
    suspend fun playlist(id: Long, musicU: String): CollectionDescriptor
    suspend fun album(id: Long, musicU: String): CollectionDescriptor
    suspend fun songDetails(ids: List<Long>, musicU: String): List<SongHit>
}

enum class CollectionItemStatus { RESOLVED, PREPARING, QUEUED, FAILED, MISSING, UNAUTHORIZED, SKIPPED }

data class CollectionResolvedItem(
    val originalIndex: Int,
    val songId: Long?,
    val status: CollectionItemStatus,
    val song: SongHit? = null,
    val message: String? = null,
)

data class CollectionResolveResult(
    val descriptor: CollectionDescriptor,
    val items: List<CollectionResolvedItem>,
    val countMismatch: Boolean,
) {
    val totalCount: Int get() = maxOf(descriptor.trackCount, items.size)
    val resolvedCount: Int get() = items.count { it.status == CollectionItemStatus.RESOLVED }
    val failedCount: Int get() = items.count {
        it.status in setOf(CollectionItemStatus.FAILED, CollectionItemStatus.UNAUTHORIZED)
    }
    val skippedCount: Int get() = items.count { it.status == CollectionItemStatus.MISSING || it.status == CollectionItemStatus.SKIPPED }
    val hasPartialFailure: Boolean get() = failedCount > 0 || skippedCount > 0 || countMismatch
}

/** Resolves missing tracks in bounded batches and restores the collection's original order. */
class CollectionResolver(
    private val api: CollectionApi,
    private val batchSize: Int = 100,
    private val maxConcurrentBatches: Int = 2,
) {
    init {
        require(batchSize in 1..100)
        require(maxConcurrentBatches in 1..4)
    }

    suspend fun resolve(reference: CollectionReference, musicU: String): CollectionResolveResult {
        val descriptor = when (reference.type) {
            CollectionSourceType.PLAYLIST -> api.playlist(reference.id, musicU)
            CollectionSourceType.ALBUM -> api.album(reference.id, musicU)
        }
        require(descriptor.reference == reference) { "集合接口返回了不匹配的资源" }

        val embeddedById = descriptor.embeddedSongs.associateBy { it.id }
        val orderedIds = descriptor.trackIds + List((descriptor.trackCount - descriptor.trackIds.size).coerceAtLeast(0)) { null }
        val missingIds = orderedIds.filterNotNull().distinct().filterNot(embeddedById::containsKey)
        val resolvedById = embeddedById.toMutableMap()
        val failuresById = mutableMapOf<Long, CollectionItemStatus>()
        val batches = missingIds.chunked(batchSize)
        val semaphore = Semaphore(maxConcurrentBatches)
        batches.chunked(maxConcurrentBatches).forEach { batchWindow ->
            val results = coroutineScope {
                batchWindow.map { ids ->
                    async {
                        try {
                            Result.success(semaphore.withPermit { api.songDetails(ids, musicU) })
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                }
            }.awaitAll()
            batchWindow.zip(results).forEach { (ids, result) ->
                result.fold(
                    onSuccess = { songs ->
                        val allowed = ids.toSet()
                        songs.filter { it.id in allowed }.forEach { resolvedById.putIfAbsent(it.id, it) }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        val status = if (error is CollectionApiException && error.kind == CollectionFailureKind.UNAUTHORIZED) {
                            CollectionItemStatus.UNAUTHORIZED
                        } else CollectionItemStatus.FAILED
                        ids.forEach { failuresById[it] = status }
                    },
                )
            }
            yield()
        }

        val items = orderedIds.mapIndexed { index, id ->
            if (id == null) return@mapIndexed CollectionResolvedItem(
                index + 1,
                null,
                CollectionItemStatus.MISSING,
                message = "接口没有返回曲目编号",
            )
            val song = resolvedById[id]
            when {
                song != null -> CollectionResolvedItem(index + 1, id, CollectionItemStatus.RESOLVED, song)
                id in failuresById -> CollectionResolvedItem(
                    index + 1,
                    id,
                    failuresById.getValue(id),
                    message = if (failuresById[id] == CollectionItemStatus.UNAUTHORIZED) "需要有效登录状态" else "曲目信息获取失败",
                )
                else -> CollectionResolvedItem(index + 1, id, CollectionItemStatus.MISSING, message = "接口未返回这首歌曲")
            }
        }
        return CollectionResolveResult(
            descriptor = descriptor,
            items = items,
            countMismatch = descriptor.trackCount != descriptor.trackIds.size,
        )
    }
}
