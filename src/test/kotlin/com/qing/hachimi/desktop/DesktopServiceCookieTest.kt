package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopServiceCookieTest {
    @Test
    fun `song URL requests include the upstream app version`() {
        val cookies = desktopSongApiCookies("synthetic-session")

        assertEquals("synthetic-session", cookies["MUSIC_U"])
        assertEquals("3.1.17.204416", cookies["appver"])
    }

    @Test
    fun `desktop service loads discovery through the authenticated adapter`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "/weapi/nuser/account/get" -> """{"code":200,"profile":{"userId":42}}"""
                    "/weapi/user/playlist" -> """{"code":200,"playlist":[{"id":17,"name":"Owned","coverImgUrl":"","trackCount":0,"creator":{"nickname":"Owner"},"subscribed":false}]}"""
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val playlists = DesktopService(httpClientOverride = client).loadMyPlaylists("synthetic-session")

        assertEquals(listOf("/weapi/nuser/account/get", "/weapi/user/playlist"), paths)
        assertEquals(17L, playlists.single().id)
        assertEquals("Owned", playlists.single().name)
    }

    @Test
    fun `desktop service maps daily recommendations from the discovery response`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/api/v3/discovery/recommend/songs", request.url.encodedPath)
                assertTrue(request.header("Cookie").orEmpty().contains("MUSIC_U=synthetic-session"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"code":200,"data":{"dailySongs":[{"id":91,"name":"Daily","ar":[{"name":"A"}],"al":{"name":"LP","picUrl":"https://p1.music.126.net/a.jpg"}}]}}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()

        val songs = DesktopService(httpClientOverride = client).loadDailyRecommendations("synthetic-session")

        assertEquals(91L, songs.single().id)
        assertEquals("LP", songs.single().album)
    }
}
