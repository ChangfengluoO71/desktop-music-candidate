package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseApi
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeteaseDiscoveryApiTest {
    @Test
    fun `account profile maps user id without exposing response`() {
        val transport = RecordingTransport { path, _, _ ->
            when (path) {
                "nuser/account/get" -> response(
                    """{"code":200,"profile":{"userId":42,"nickname":"private-profile-marker"}}""",
                )
                "user/playlist" -> response(
                    """{"code":200,"playlist":[{"id":17,"name":"Owned","coverImgUrl":"https://p1.music.126.net/a.jpg","trackCount":3,"creator":{"nickname":"Owner"},"subscribed":false}]}""",
                )
                else -> error("Unexpected endpoint")
            }
        }

        val playlists = NeteaseDiscoveryApi(transport).myPlaylists("synthetic-session-secret")

        assertEquals(42L, JSONObject(transport.calls[1].payload).getLong("uid"))
        assertEquals("Owned", playlists.single().name)
        assertFalse(playlists.toString().contains("private-profile-marker"))
        assertFalse(playlists.toString().contains("synthetic-session-secret"))
    }

    @Test
    fun `my playlists paginates in batches of 50`() {
        var playlistPage = 0
        val transport = RecordingTransport { path, payload, _ ->
            when (path) {
                "nuser/account/get" -> response("""{"code":200,"profile":{"userId":7}}""")
                "user/playlist" -> {
                    val offset = JSONObject(payload).getInt("offset")
                    playlistPage += 1
                    val count = if (offset == 0) 50 else 3
                    val rows = (offset + 1 until offset + count + 1).joinToString(",") { id ->
                        """{"id":$id,"name":"Playlist $id","coverImgUrl":"","trackCount":0,"creator":{"nickname":"Owner"},"subscribed":false}"""
                    }
                    response("""{"code":200,"playlist":[$rows]}""")
                }
                else -> error("Unexpected endpoint")
            }
        }

        val playlists = NeteaseDiscoveryApi(transport).myPlaylists("synthetic-session")

        assertEquals(listOf(0, 50), transport.calls.filter { it.path == "user/playlist" }.map {
            JSONObject(it.payload).getInt("offset")
        })
        assertEquals(53, playlists.size)
        assertEquals(2, playlistPage)
    }

    @Test
    fun `daily recommendations map song and cover fields`() {
        val transport = RecordingTransport { path, _, _ ->
            assertEquals("api/v3/discovery/recommend/songs", path)
            response(
                """{"code":200,"data":{"dailySongs":[{"id":901,"name":"Recommended","ar":[{"name":"Artist A"},{"name":"Artist B"}],"al":{"name":"Album","picUrl":"https://p1.music.126.net/cover.jpg"}}]}}""",
            )
        }

        val song = NeteaseDiscoveryApi(transport).dailyRecommendations("synthetic-session").single()

        assertEquals(901L, song.id)
        assertEquals("Recommended", song.name)
        assertEquals("Artist A, Artist B", song.artists)
        assertEquals("Album", song.album)
        assertEquals("https://p1.music.126.net/cover.jpg", song.coverUrl)
    }

    @Test
    fun `empty lists return empty results`() {
        val transport = RecordingTransport { path, _, _ ->
            when (path) {
                "nuser/account/get" -> response("""{"code":200,"profile":{"userId":8}}""")
                "user/playlist" -> response("""{"code":200,"playlist":[]}""")
                "api/v3/discovery/recommend/songs" -> response("""{"code":200,"data":{"dailySongs":[]}}""")
                else -> error("Unexpected endpoint")
            }
        }
        val api = NeteaseDiscoveryApi(transport)

        assertTrue(api.myPlaylists("synthetic-session").isEmpty())
        assertTrue(api.dailyRecommendations("synthetic-session").isEmpty())
    }

    @Test
    fun `301 and 401 become session expired`() {
        for (status in listOf(301, 401)) {
            val api = NeteaseDiscoveryApi(
                RecordingTransport { _, _, _ -> DiscoveryHttpResponse(status, "private-response-and-session") },
            )

            val error = assertFailsWith<SessionExpiredException> {
                api.dailyRecommendations("synthetic-session-secret")
            }

            assertFalse(error.message.orEmpty().contains("synthetic-session-secret"))
            assertFalse(error.message.orEmpty().contains("private-response-and-session"))
        }
    }

    @Test
    fun `malformed JSON returns a sanitized error`() {
        val api = NeteaseDiscoveryApi(
            RecordingTransport { _, _, _ -> DiscoveryHttpResponse(200, "private-response-marker { broken") },
        )

        val error = assertFailsWith<DiscoveryApiException> {
            api.dailyRecommendations("synthetic-session-secret")
        }

        assertFalse(error.message.orEmpty().contains("private-response-marker"))
        assertFalse(error.message.orEmpty().contains("synthetic-session-secret"))
        assertEquals(DiscoveryFailureKind.MALFORMED_RESPONSE, error.kind)
    }

    @Test
    fun `HTTP transport keeps session on the NetEase API host and does not follow redirects`() {
        var observedRequest: Request? = null
        var requestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount += 1
                observedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://untrusted.example/private")
                    .body("redirect-marker".toResponseBody())
                    .build()
            }
            .build()
        val transport = OkHttpDiscoveryRequestTransport(NeteaseApi(client))

        val response = transport.post(
            path = "api/v3/discovery/recommend/songs",
            payload = "",
            musicU = "synthetic-session-secret",
            encrypted = false,
        )

        val request = requireNotNull(observedRequest)
        assertEquals(302, response.status)
        assertEquals(1, requestCount)
        assertEquals("music.163.com", request.url.host)
        assertTrue(request.header("Cookie").orEmpty().contains("synthetic-session-secret"))
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        assertFalse(body.contains("synthetic-session-secret"))
    }

    private class RecordingTransport(
        private val handler: (path: String, payload: String, musicU: String) -> DiscoveryHttpResponse,
    ) : DiscoveryRequestTransport {
        data class Call(val path: String, val payload: String)

        val calls = mutableListOf<Call>()

        override fun post(
            path: String,
            payload: String,
            musicU: String,
            encrypted: Boolean,
        ): DiscoveryHttpResponse {
            calls += Call(path, payload)
            return handler(path, payload, musicU)
        }
    }

    private fun response(body: String) = DiscoveryHttpResponse(200, body)

}
