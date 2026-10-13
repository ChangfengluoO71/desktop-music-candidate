package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseApi
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NeteaseCollectionApiTest {
    private val api = NeteaseCollectionApi(NeteaseApi(OkHttpClient()))

    @Test
    fun playlistParserRetainsOrderDuplicatesAndUnknownTrackSlots() {
        val result = api.parsePlaylistResponse(
            91L,
            """{"code":200,"playlist":{"id":91,"name":"Example","coverImgUrl":"https://p1.music.126.net/cover.jpg","trackCount":4,"creator":{"nickname":"Owner"},"trackIds":[{"id":10},{"id":20},{"id":20},{}],"tracks":[{"id":10,"name":"Ten","ar":[{"name":"Artist"}],"al":{"name":"Album","picUrl":"https://p1.music.126.net/a.jpg"}}]}}""",
        )

        assertEquals("Example", result.name)
        assertEquals("Owner", result.creator)
        assertEquals(listOf(10L, 20L, 20L, null), result.trackIds)
        assertEquals(listOf(10L), result.embeddedSongs.map { it.id })
        assertEquals("Artist", result.embeddedSongs.single().artists)
        assertEquals("Album", result.embeddedSongs.single().album)
    }

    @Test
    fun albumParserProvidesTrackOrderTotalsAndCoverMetadata() {
        val result = api.parseAlbumResponse(
            7L,
            """{"code":200,"album":{"id":7,"name":"LP","picUrl":"https://p1.music.126.net/lp.jpg","size":2,"artist":{"name":"Album Artist"}},"songs":[{"id":31,"name":"First","ar":[{"name":"A"}],"al":{"name":"LP","picUrl":"https://p1.music.126.net/lp.jpg"}},{"id":32,"name":"Second","ar":[{"name":"B"}],"al":{"name":"LP","picUrl":"https://p1.music.126.net/lp.jpg"}}]}""",
        )

        assertEquals(CollectionSourceType.ALBUM, result.reference.type)
        assertEquals("Album Artist", result.creator)
        assertEquals("https://p1.music.126.net/lp.jpg", result.coverUrl)
        assertEquals(listOf(31L, 32L), result.trackIds)
        assertEquals(listOf(1, 2), result.embeddedSongs.map { it.trackNumber })
        assertEquals(listOf(2, 2), result.embeddedSongs.map { it.trackTotal })
    }

    @Test
    fun unauthorizedResponseIsNotTreatedAsAnEmptyCollection() {
        val error = assertFailsWith<CollectionApiException> {
            api.parsePlaylistResponse(12L, """{"code":301,"playlist":null}""")
        }
        assertEquals(CollectionFailureKind.UNAUTHORIZED, error.kind)
    }
}
