package com.qing.hachimi.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

class CollectionResolverTest {
    @Test
    fun parsesOnlySupportedNeteaseCollectionUrls() {
        assertEquals(
            CollectionReference(CollectionSourceType.PLAYLIST, 123456L),
            NeteaseCollectionLinkParser.parse("https://music.163.com/#/playlist?id=123456"),
        )
        assertEquals(
            CollectionReference(CollectionSourceType.ALBUM, 987L),
            NeteaseCollectionLinkParser.parse("http://music.163.com/album?id=987&uct2=ignored"),
        )
        assertNull(NeteaseCollectionLinkParser.parse("https://music.163.com.evil.example/playlist?id=1"))
        assertNull(NeteaseCollectionLinkParser.parse("https://example.com/playlist?id=1"))
        assertNull(NeteaseCollectionLinkParser.parse("https://music.163.com/playlist?id=abc"))
    }

    @Test
    fun preservesTrackOrderAndDuplicateMembershipAcrossPartialFailures() = runBlocking {
        val api = object : CollectionApi {
            override suspend fun playlist(id: Long, musicU: String): CollectionDescriptor =
                CollectionDescriptor(
                    reference = CollectionReference(CollectionSourceType.PLAYLIST, id),
                    name = "Mixed",
                    trackCount = 5,
                    trackIds = listOf(10, 20, 10, 30, 40),
                    embeddedSongs = listOf(song(10)),
                )

            override suspend fun album(id: Long, musicU: String): CollectionDescriptor = error("unused")

            override suspend fun songDetails(ids: List<Long>, musicU: String): List<SongHit> = when (ids) {
                listOf(20L, 30L) -> listOf(song(20))
                listOf(40L) -> throw CollectionApiException(CollectionFailureKind.UNAUTHORIZED)
                else -> error("unexpected batch $ids")
            }
        }

        val result = CollectionResolver(api, batchSize = 2, maxConcurrentBatches = 2)
            .resolve(CollectionReference(CollectionSourceType.PLAYLIST, 55), "session")

        assertEquals(listOf(10L, 20L, 10L, 30L, 40L), result.items.map { it.songId })
        assertEquals(
            listOf(
                CollectionItemStatus.RESOLVED,
                CollectionItemStatus.RESOLVED,
                CollectionItemStatus.RESOLVED,
                CollectionItemStatus.MISSING,
                CollectionItemStatus.UNAUTHORIZED,
            ),
            result.items.map { it.status },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), result.items.map { it.originalIndex })
        assertEquals(5, result.totalCount)
        assertEquals(3, result.resolvedCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.skippedCount)
        assertTrue(result.hasPartialFailure)
    }

    @Test
    fun emptyAndSingleTrackCollectionsHaveAccurateSummaries() = runBlocking {
        for (count in listOf(0, 1)) {
            val reference = CollectionReference(CollectionSourceType.PLAYLIST, 80L + count)
            val onlySong = song(501)
            val api = object : CollectionApi {
                override suspend fun playlist(id: Long, musicU: String) = CollectionDescriptor(
                    reference = reference,
                    name = "Count $count",
                    trackCount = count,
                    trackIds = if (count == 0) emptyList() else listOf(onlySong.id),
                    embeddedSongs = if (count == 0) emptyList() else listOf(onlySong),
                )
                override suspend fun album(id: Long, musicU: String): CollectionDescriptor = error("unused")
                override suspend fun songDetails(ids: List<Long>, musicU: String): List<SongHit> = error("unused")
            }

            val result = CollectionResolver(api).resolve(reference, "session")

            assertEquals(count, result.totalCount)
            assertEquals(count, result.resolvedCount)
            assertEquals(0, result.failedCount)
            assertEquals(0, result.skippedCount)
            assertFalse(result.hasPartialFailure)
        }
    }

    @Test
    fun largePlaylistUsesBoundedBatchesAndKeepsAllRows() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val api = object : CollectionApi {
            override suspend fun playlist(id: Long, musicU: String) = CollectionDescriptor(
                reference = CollectionReference(CollectionSourceType.PLAYLIST, id),
                name = "Large",
                trackCount = 205,
                trackIds = (1L..205L).toList(),
            )

            override suspend fun album(id: Long, musicU: String): CollectionDescriptor = error("unused")

            override suspend fun songDetails(ids: List<Long>, musicU: String): List<SongHit> {
                calls.incrementAndGet()
                check(ids.size <= 100)
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(5)
                active.decrementAndGet()
                return ids.map(::song)
            }
        }

        val result = CollectionResolver(api, batchSize = 100, maxConcurrentBatches = 2)
            .resolve(CollectionReference(CollectionSourceType.PLAYLIST, 99), "session")

        assertEquals(205, result.items.size)
        assertEquals(205, result.resolvedCount)
        assertEquals(3, calls.get())
        assertEquals(2, peak.get())
        assertFalse(result.hasPartialFailure)
    }

    private fun song(id: Long) = SongHit(id, "Song $id", "Artist", "Album")
}
