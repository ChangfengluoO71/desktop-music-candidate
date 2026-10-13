package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Desktop-only adapter for the upstream collection endpoints; it contains no Android types. */
class NeteaseCollectionApi(private val api: NeteaseApi) : CollectionApi {
    override suspend fun playlist(id: Long, musicU: String): CollectionDescriptor = withContext(Dispatchers.IO) {
        require(id > 0L)
        val raw = api.eapiPost(
            "v6/playlist/detail",
            JSONObject().put("id", id).put("n", 100000).put("s", 8).toString(),
            cookies(musicU),
            callTimeoutMs = 15_000L,
        )
        parsePlaylistResponse(id, raw)
    }

    internal fun parsePlaylistResponse(id: Long, raw: String): CollectionDescriptor {
        val root = JSONObject(raw)
        requireSuccess(root)
        val playlist = root.optJSONObject("playlist")
            ?: throw CollectionApiException(CollectionFailureKind.MALFORMED, "歌单详情格式无效")
        val ids = playlist.optJSONArray("trackIds")?.let(::parseTrackIds).orEmpty()
        val tracks = playlist.optJSONArray("tracks")?.let(::parseSongArray).orEmpty()
        return CollectionDescriptor(
            reference = CollectionReference(CollectionSourceType.PLAYLIST, id),
            name = playlist.optString("name").ifBlank { "未命名歌单" },
            creator = playlist.optJSONObject("creator")?.optString("nickname").orEmpty(),
            coverUrl = playlist.optString("coverImgUrl"),
            trackCount = playlist.optInt("trackCount", ids.size),
            trackIds = ids.ifEmpty { tracks.map { it.id } },
            embeddedSongs = tracks,
            description = playlist.optString("description"),
        )
    }

    override suspend fun album(id: Long, musicU: String): CollectionDescriptor = withContext(Dispatchers.IO) {
        require(id > 0L)
        val raw = api.post(
            "https://music.163.com/api/v1/album/$id",
            api.formBody(),
            cookies(musicU),
            callTimeoutMs = 15_000L,
        )
        parseAlbumResponse(id, raw)
    }

    internal fun parseAlbumResponse(id: Long, raw: String): CollectionDescriptor {
        val root = JSONObject(raw)
        requireSuccess(root)
        val album = root.optJSONObject("album")
            ?: throw CollectionApiException(CollectionFailureKind.MALFORMED, "专辑详情格式无效")
        val songs = root.optJSONArray("songs") ?: JSONArray()
        val parsedSongs = parseSongArray(songs).mapIndexed { index, song -> song.copy(trackNumber = index + 1, trackTotal = songs.length()) }
        return CollectionDescriptor(
            reference = CollectionReference(CollectionSourceType.ALBUM, id),
            name = album.optString("name").ifBlank { "未命名专辑" },
            creator = album.optJSONObject("artist")?.optString("name")
                ?: album.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
            coverUrl = album.optString("picUrl"),
            trackCount = album.optInt("size", songs.length()),
            trackIds = parsedSongs.map { it.id },
            embeddedSongs = parsedSongs,
            description = album.optString("description").ifBlank { album.optString("briefDesc") },
        )
    }

    override suspend fun songDetails(ids: List<Long>, musicU: String): List<SongHit> = withContext(Dispatchers.IO) {
        require(ids.isNotEmpty() && ids.size <= 100)
        val c = JSONArray().apply { ids.forEach { put(JSONObject().put("id", it).put("v", 0)) } }
        val raw = api.eapiPost(
            "v3/song/detail",
            JSONObject().put("c", c).toString(),
            cookies(musicU),
            callTimeoutMs = 15_000L,
        )
        parseSongDetailsResponse(raw)
    }

    internal fun parseSongDetailsResponse(raw: String): List<SongHit> {
        val root = JSONObject(raw)
        requireSuccess(root)
        return parseSongArray(root.optJSONArray("songs") ?: JSONArray())
    }

    private fun parseTrackIds(rows: JSONArray): List<Long?> = (0 until rows.length()).map { index ->
        rows.optJSONObject(index)?.optLong("id")?.takeIf { it > 0L }
    }

    private fun parseSongArray(rows: JSONArray): List<SongHit> = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optLong("id").takeIf { it > 0L } ?: return@mapNotNull null
        val artists = (row.optJSONArray("ar") ?: row.optJSONArray("artists"))?.let { values ->
            (0 until values.length()).mapNotNull { values.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString("、")
        }.orEmpty()
        val album = row.optJSONObject("al") ?: row.optJSONObject("album")
        SongHit(
            id = id,
            name = row.optString("name").ifBlank { "未知歌曲" },
            artists = artists,
            album = album?.optString("name").orEmpty(),
            coverUrl = album?.optString("picUrl").orEmpty(),
        )
    }

    private fun requireSuccess(root: JSONObject) {
        when (val code = root.optLong("code")) {
            200L -> Unit
            301L, 401L, 403L -> throw CollectionApiException(CollectionFailureKind.UNAUTHORIZED)
            404L -> throw CollectionApiException(CollectionFailureKind.NOT_FOUND, "找不到歌单或专辑")
            else -> if (code != 0L) throw CollectionApiException(
                CollectionFailureKind.NETWORK,
                "集合接口暂时不可用 (code=$code)",
            ) else throw IOException("集合接口响应缺少状态码")
        }
    }

    private fun cookies(musicU: String): Map<String, String> =
        musicU.trim().takeIf(String::isNotEmpty)?.let { mapOf("MUSIC_U" to it) }.orEmpty()
}
