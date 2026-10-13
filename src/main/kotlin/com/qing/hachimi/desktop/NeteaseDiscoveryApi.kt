package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseApi
import com.qing.hachimi.data.api.NeteaseCrypto
import okhttp3.CookieJar
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class UserPlaylistSummary(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
    val creator: String,
    val subscribed: Boolean = false,
)

enum class DiscoveryFailureKind { NETWORK, HTTP_STATUS, BUSINESS_CODE, MALFORMED_RESPONSE }

class DiscoveryApiException(
    val kind: DiscoveryFailureKind,
    message: String,
) : IOException(message)

internal data class DiscoveryHttpResponse(val status: Int, val body: String)

/** A small seam keeps endpoint parsing and pagination testable without live NetEase requests. */
internal fun interface DiscoveryRequestTransport {
    fun post(path: String, payload: String, musicU: String, encrypted: Boolean): DiscoveryHttpResponse
}

class NeteaseDiscoveryApi internal constructor(private val transport: DiscoveryRequestTransport) {
    fun myPlaylists(musicU: String): List<UserPlaylistSummary> {
        requireSession(musicU)
        val account = requestJson(ACCOUNT_PATH, "{}", musicU, encrypted = true)
        val userId = account.optJSONObject("profile")?.optLong("userId")?.takeIf { it > 0L }
            ?: throw DiscoveryApiException(
                DiscoveryFailureKind.MALFORMED_RESPONSE,
                "账号资料响应缺少有效用户编号。",
            )

        val playlists = mutableListOf<UserPlaylistSummary>()
        var offset = 0
        while (true) {
            val payload = JSONObject()
                .put("uid", userId)
                .put("limit", PAGE_SIZE)
                .put("offset", offset)
                .put("includeVideo", true)
                .toString()
            val root = requestJson(PLAYLIST_PATH, payload, musicU, encrypted = true)
            val rows = root.optJSONArray("playlist")
                ?: throw DiscoveryApiException(
                    DiscoveryFailureKind.MALFORMED_RESPONSE,
                    "歌单响应缺少歌单列表。",
                )
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optLong("id").takeIf { it > 0L } ?: continue
                playlists += UserPlaylistSummary(
                    id = id,
                    name = row.optString("name"),
                    coverUrl = row.optString("coverImgUrl").ifBlank { row.optString("coverUrl") },
                    trackCount = row.optInt("trackCount").coerceAtLeast(0),
                    creator = row.optJSONObject("creator")?.optString("nickname").orEmpty(),
                    subscribed = row.optBoolean("subscribed"),
                )
            }
            if (rows.length() < PAGE_SIZE) break
            offset += rows.length()
        }
        return playlists
    }

    fun dailyRecommendations(musicU: String): List<SongHit> {
        requireSession(musicU)
        val root = requestJson(DAILY_RECOMMENDATIONS_PATH, "", musicU, encrypted = false)
        val data = root.optJSONObject("data")
            ?: throw DiscoveryApiException(
                DiscoveryFailureKind.MALFORMED_RESPONSE,
                "每日推荐响应缺少歌曲数据。",
            )
        val rows = data.optJSONArray("dailySongs") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optLong("id").takeIf { it > 0L } ?: return@mapNotNull null
            val artists = (row.optJSONArray("ar") ?: row.optJSONArray("artists"))?.let { values ->
                (0 until values.length())
                    .mapNotNull { values.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
            }.orEmpty()
            val album = row.optJSONObject("al") ?: row.optJSONObject("album")
            SongHit(
                id = id,
                name = row.optString("name"),
                artists = artists,
                album = album?.optString("name").orEmpty(),
                coverUrl = album?.optString("picUrl")
                    ?.ifBlank { album.optString("coverUrl") }
                    .orEmpty(),
            )
        }
    }

    private fun requestJson(
        path: String,
        payload: String,
        musicU: String,
        encrypted: Boolean,
    ): JSONObject {
        val response = try {
            transport.post(path, payload, musicU.trim(), encrypted)
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (_: IOException) {
            throw DiscoveryApiException(DiscoveryFailureKind.NETWORK, "连接发现服务失败，请检查网络后重试。")
        } catch (_: Exception) {
            throw DiscoveryApiException(DiscoveryFailureKind.NETWORK, "连接发现服务失败，请检查网络后重试。")
        }
        if (response.status == 301 || response.status == 401 || response.status == 403) {
            throw SessionExpiredException()
        }
        if (response.status !in 200..299) {
            throw DiscoveryApiException(
                DiscoveryFailureKind.HTTP_STATUS,
                "发现服务暂不可用（HTTP ${response.status}）。",
            )
        }

        val root = try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            throw DiscoveryApiException(
                DiscoveryFailureKind.MALFORMED_RESPONSE,
                "发现服务返回的数据格式无效。",
            )
        }
        val code = root.optLong("code", Long.MIN_VALUE)
        if (code == 301L || code == 401L || code == 403L) throw SessionExpiredException()
        if (code != 200L) {
            val kind = if (code == Long.MIN_VALUE) {
                DiscoveryFailureKind.MALFORMED_RESPONSE
            } else {
                DiscoveryFailureKind.BUSINESS_CODE
            }
            throw DiscoveryApiException(kind, "发现服务暂不可用（业务状态 $code）。")
        }
        return root
    }

    private fun requireSession(musicU: String) {
        if (musicU.isBlank() || musicU.any { it.isWhitespace() || it == ';' || it == '\r' || it == '\n' }) {
            throw SessionExpiredException()
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val ACCOUNT_PATH = "nuser/account/get"
        const val PLAYLIST_PATH = "user/playlist"
        const val DAILY_RECOMMENDATIONS_PATH = "api/v3/discovery/recommend/songs"
    }
}

/** Sends authenticated discovery requests without the Android API wrapper's body logging. */
internal class OkHttpDiscoveryRequestTransport(private val api: NeteaseApi) : DiscoveryRequestTransport {
    private val client = api.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override fun post(
        path: String,
        payload: String,
        musicU: String,
        encrypted: Boolean,
    ): DiscoveryHttpResponse {
        val credential = musicU.trim()
        if (credential.isBlank() || credential.any { it.isWhitespace() || it == ';' || it == '\r' || it == '\n' }) {
            throw SessionExpiredException()
        }
        val url = when {
            encrypted && path in setOf("nuser/account/get", "user/playlist") -> "${NeteaseApi.BASE_URL}/weapi/$path"
            !encrypted && path == "api/v3/discovery/recommend/songs" -> "${NeteaseApi.BASE_URL}/$path"
            else -> throw IllegalArgumentException("Unsupported discovery endpoint")
        }
        val body = if (encrypted) {
            val weapiPayload = JSONObject(payload).put("csrf_token", "").toString()
            val parameters = NeteaseCrypto.encryptWeapi(weapiPayload)
            api.formBody("params", parameters.getValue("params"), "encSecKey", parameters.getValue("encSecKey"))
        } else {
            api.formBody()
        }
        val cookieHeader = apiCookieHeader(credential)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "${NeteaseApi.BASE_URL}/")
            .header("Cookie", cookieHeader)
            .post(body)
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            DiscoveryHttpResponse(response.code, response.body.string())
        }
    }

    private fun apiCookieHeader(musicU: String): String =
        "MUSIC_U=${URLEncoder.encode(musicU, Charsets.UTF_8)}; appver=$NETEASE_APP_VERSION"

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }
}
