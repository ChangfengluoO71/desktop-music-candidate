package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseApi
import com.qing.hachimi.data.api.NeteaseCrypto
import com.qing.hachimi.data.api.SongApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SongHit(
    val id: Long,
    val name: String,
    val artists: String,
    val album: String,
    val coverUrl: String = "",
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val releaseYear: Int? = null,
    val albumArtist: String = "",
    val aliases: List<String> = emptyList(),
    val translatedTitles: List<String> = emptyList(),
)

@ConsistentCopyVisibility
data class ResolvedSongUrl internal constructor(
    val url: String,
    val type: String?,
    val actualQuality: String,
    internal val origin: AudioUrlOrigin = AudioUrlOrigin.UNTRUSTED,
)

internal const val NETEASE_APP_VERSION = "3.1.17.204416"

/** Supplies fields embedded by upstream SongApi in its EAPI player request. */
internal fun desktopSongApiCookies(musicU: String): Map<String, String> {
    val token = musicU.trim()
    return if (token.isBlank()) {
        emptyMap()
    } else {
        mapOf(
            "MUSIC_U" to token,
            // SongApi's nested player header reads this from the cookie map and otherwise
            // sends appVer="" even though NeteaseApi adds appver to the HTTP Cookie header.
            "appver" to NETEASE_APP_VERSION,
        )
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

interface DesktopGateway : SongSourceResolver {
    suspend fun searchSongs(query: String, musicU: String): List<SongHit>
    suspend fun validateSession(musicU: String): SessionValidation
    suspend fun loadMyPlaylists(musicU: String): List<UserPlaylistSummary> =
        throw UnsupportedOperationException("当前数据源不支持读取个人歌单")
    suspend fun loadDailyRecommendations(musicU: String): List<SongHit> =
        throw UnsupportedOperationException("当前数据源不支持每日推荐")
    suspend fun resolveCollection(reference: CollectionReference, musicU: String): CollectionResolveResult =
        throw UnsupportedOperationException("该桌面数据源不支持歌单或专辑导入")
    suspend fun fetchArtwork(url: String): ByteArray? = null
    suspend fun fetchArtworkPayload(url: String): ArtworkPayload? =
        fetchArtwork(url)?.let(::artworkPayloadFromTrustedBytes)
    suspend fun fetchArtworkForPreview(url: String): ArtworkPayload? = fetchArtworkPayload(url)
    suspend fun loadPostProcessingData(
        task: DownloadTask,
        options: AudioPostProcessingOptions,
        musicU: String?,
    ): DesktopPostProcessingData = DesktopPostProcessingData()
}

/**
 * First desktop vertical slice. Uses the upstream NeteaseApi + SongApi unmodified.
 * All cookies are volatile and NEVER persisted by this module.
 */
class DesktopService(
    private val diagnostics: DownloadDiagnosticsStore? = null,
    httpClientOverride: OkHttpClient? = null,
    private val sessionValidatorOverride: (suspend (String) -> SessionValidation)? = null,
    private val networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(),
) : DesktopGateway {
    private val audioUrlPolicy = AudioUrlPolicy()
    private val resolvingTaskId = ThreadLocal<String?>()
    private val resolvingCalls = ConcurrentHashMap<String, Call>()
    private val sourceTraceContext = ThreadLocal<SourceRequestTraceContext?>()
    private val sourceObservation = ThreadLocal<SourceApiObservation?>()
    private val sourceRequestFailure = ThreadLocal<SourceResolutionFailure?>()
    private val http = networkFactory.configure(httpClientOverride?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .addInterceptor(
            SourceResponseDiagnosticsInterceptor(
                diagnostics = diagnostics,
                currentContext = { sourceTraceContext.get() },
                onRequestFailure = { _, failure, _ -> sourceRequestFailure.set(failure) },
                onObservation = { _, observation, _ -> sourceObservation.set(observation) },
            ),
        )
        .eventListenerFactory { call ->
            object : EventListener() {
                override fun callStart(call: Call) {
                    resolvingTaskId.get()?.let { resolvingCalls[it] = call }
                }

                override fun callEnd(call: Call) {
                    resolvingTaskId.get()?.let { resolvingCalls.remove(it, call) }
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    resolvingTaskId.get()?.let { resolvingCalls.remove(it, call) }
                }
            }
        }
        .build()
    private val api = NeteaseApi(http)
    private val songApi = SongApi(api)
    // Artwork has a dedicated cookie-free client and does not reuse the authenticated API client.
    private val artworkFetcher = ArtworkFetcher(
        allowLegacyNetEaseHttpUrls = true,
        networkFactory = networkFactory,
    )
    private val discoveryApi = NeteaseDiscoveryApi(OkHttpDiscoveryRequestTransport(api))
    private val collectionResolver = CollectionResolver(NeteaseCollectionApi(api))

    override suspend fun searchSongs(query: String, musicU: String): List<SongHit> = withContext(Dispatchers.IO) {
        require(query.trim().isNotEmpty()) { "请输入搜索关键词" }
        val raw = api.post(
            "https://music.163.com/api/cloudsearch/pc",
            api.formBody("s", query.trim(), "type", "1", "limit", "50", "offset", "0", "total", "true"),
            cookies(musicU),
            15_000L,
        )
        val obj = JSONObject(raw)
        if (obj.optLong("code") == 301L || obj.optLong("code") == 401L) {
            throw SessionExpiredException()
        }
        if (obj.optLong("code") != 200L) throw IOException("搜索接口不可用 (code=${obj.optLong("code")})")
        val items = obj.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optLong("id")
            if (id <= 0L) return@mapNotNull null
            val artists = row.optJSONArray("ar")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString("、")
            }.orEmpty()
            val album = row.optJSONObject("al")
            SongHit(
                id = id,
                name = row.optString("name"),
                artists = artists,
                album = album?.optString("name").orEmpty(),
                coverUrl = album?.optString("picUrl").orEmpty(),
            )
        }
    }

    override suspend fun resolveCollection(
        reference: CollectionReference,
        musicU: String,
    ): CollectionResolveResult = collectionResolver.resolve(reference, musicU)

    override suspend fun loadMyPlaylists(musicU: String): List<UserPlaylistSummary> = withContext(Dispatchers.IO) {
        discoveryApi.myPlaylists(musicU)
    }

    override suspend fun loadDailyRecommendations(musicU: String): List<SongHit> = withContext(Dispatchers.IO) {
        discoveryApi.dailyRecommendations(musicU)
    }

    override suspend fun fetchArtwork(url: String): ByteArray? = fetchArtworkPayload(url)?.bytes

    override suspend fun fetchArtworkPayload(url: String): ArtworkPayload? = withContext(Dispatchers.IO) {
        artworkFetcher.fetch(url)
    }

    override suspend fun fetchArtworkForPreview(url: String): ArtworkPayload? = withContext(Dispatchers.IO) {
        artworkFetcher.fetchOrThrow(url)
    }

    override suspend fun loadPostProcessingData(
        task: DownloadTask,
        options: AudioPostProcessingOptions,
        musicU: String?,
    ): DesktopPostProcessingData = withContext(Dispatchers.IO) {
        val token = musicU.orEmpty()
        val cookieMap = cookies(token)
        val detail = songApi.getDetail(task.songId.toString(), cookieMap)
        if (detail == null && token.isNotBlank() && validateSession(token) == SessionValidation.EXPIRED) {
            throw SessionExpiredException()
        }
        val needLyrics = options.downloadLyrics || options.embedLyrics ||
            options.saveTranslatedLyrics || options.saveRomanizedLyrics || options.saveWordLevelLyrics
        val lyrics = if (needLyrics) songApi.getLyricFull(task.songId.toString(), cookieMap) else null
        val creators = if (options.writeAudioTags) songApi.getCreators(task.songId.toString(), cookieMap) else null
        var cover: ArtworkPayload? = null
        var coverError: String? = null
        if (options.embedCoverArt) {
            val coverUrls = listOf(detail?.coverUrl, task.coverUrl)
                .filterNotNull()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
            if (coverUrls.isEmpty()) {
                coverError = "歌曲详情和任务记录均未提供封面地址。"
            } else {
                val failures = mutableListOf<String>()
                for (coverUrl in coverUrls) {
                    try {
                        cover = fetchArtworkForPreview(coverUrl)
                        if (cover != null) break
                        failures += "封面服务未返回图片数据。"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: ArtworkFetchException) {
                        failures += error.safeMessage
                    } catch (_: Exception) {
                        failures += "封面请求失败；请检查网络或代理设置。"
                    }
                }
                if (cover == null) coverError = failures.lastOrNull() ?: "封面暂时不可用。"
            }
        }
        DesktopPostProcessingData(
            lyrics = lyrics?.let {
                DesktopLyrics(
                    lrc = it.lrc,
                    translation = it.tlyric,
                    romanization = it.romalrc,
                    wordLevel = it.yrc,
                    wordLevelTranslation = it.ytlrc,
                    wordLevelRomanization = it.yromalrc,
                )
            },
            tags = DesktopTagMetadata(
                albumArtist = detail?.albumArtist.orEmpty(),
                trackNumber = detail?.trackNumber,
                trackTotal = detail?.trackTotal,
                discNumber = detail?.discNumber,
                discTotal = detail?.discTotal,
                releaseYear = detail?.releaseYear,
                aliases = detail?.aliases.orEmpty(),
                translatedTitles = detail?.translatedTitles.orEmpty(),
                lyricists = creators?.lyricists.orEmpty(),
                composers = creators?.composers.orEmpty(),
                arrangers = creators?.arrangers.orEmpty(),
                producers = creators?.producers.orEmpty(),
            ),
            coverBytes = cover?.bytes,
            coverMimeType = cover?.mimeType,
            coverError = coverError,
        )
    }

    /** Performs the existing WEAPI account check without logging the credential or response body. */
    override suspend fun validateSession(musicU: String): SessionValidation = withContext(Dispatchers.IO) {
        if (musicU.isBlank()) return@withContext SessionValidation.EXPIRED
        try {
            val cookies = cookies(musicU)
            val encrypted = NeteaseCrypto.encryptWeapi(JSONObject().put("csrf_token", "").toString())
            val request = Request.Builder()
                .url("https://music.163.com/weapi/nuser/account/get")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", api.cookieHeader(cookies))
                .post(api.formBody("params", encrypted.getValue("params"), "encSecKey", encrypted.getValue("encSecKey")))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) return@withContext SessionValidation.EXPIRED
                if (!response.isSuccessful) return@withContext SessionValidation.TEMPORARY_FAILURE
                val body = response.body.string()
                val json = JSONObject(body)
                when {
                    json.optLong("code") == 200L &&
                        (json.optJSONObject("profile")?.optLong("userId") ?: 0L) > 0L ->
                        SessionValidation.VALID
                    json.optLong("code") == 301L || json.optLong("code") == 401L ->
                        SessionValidation.EXPIRED
                    else -> SessionValidation.TEMPORARY_FAILURE
                }
            }
        } catch (_: Exception) {
            SessionValidation.TEMPORARY_FAILURE
        }
    }

    /** No attempt to bypass paywalls/DRM. Null URLs are treated as unavailable. */
    fun resolveSongUrl(id: Long, requestedQuality: String, musicU: String): ResolvedSongUrl {
        require(id > 0L)
        require(requestedQuality in NeteaseApi.QUALITY_MAP) { "不支持该音质" }
        for (quality in NeteaseApi.fallbackQuality(requestedQuality)) {
            val result = songApi.getUrl(id.toString(), quality, cookies(musicU)) ?: continue
            val candidate = result.url?.trim().orEmpty()
            if (candidate.isEmpty()) continue
            val metadata = audioUrlPolicy.inspect(candidate, AudioUrlOrigin.NETEASE_SOURCE_API)
            try {
                audioUrlPolicy.prepare(candidate, AudioUrlOrigin.NETEASE_SOURCE_API)
            } catch (error: AudioUrlPolicyException) {
                throw SourceResolutionException(
                    SourceResolutionFailure.AudioUrlPolicy(error.failure, metadata.validationRule),
                    actualQuality = result.level,
                )
            }
            return ResolvedSongUrl(
                candidate,
                result.type,
                result.level ?: quality,
                AudioUrlOrigin.NETEASE_SOURCE_API,
            )
        }
        throw IOException("没有可用的授权音源；可能需要登录、权益或歌曲本身不可下载")
    }

    override suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl =
        withContext(Dispatchers.IO) {
            require(task.songId > 0L)
            resolvingTaskId.set(task.id)
            val resolveStarted = System.nanoTime()
            var lastFailure: SourceResolutionFailure? = null
            try {
                for (quality in NeteaseApi.fallbackQuality(task.requestedQuality)) {
                    currentCoroutineContext().ensureActive()
                    sourceObservation.remove()
                    sourceRequestFailure.remove()
                    sourceTraceContext.set(
                        SourceRequestTraceContext(
                            taskId = task.id,
                            songId = task.songId,
                            requestedQuality = task.requestedQuality,
                            attemptQuality = quality,
                        ),
                    )
                    recordDiagnostic(
                        task,
                        DownloadDiagnosticStage.SOURCE_RESOLVING,
                        0L,
                        DownloadDiagnosticOutcome.STARTED,
                        attemptQuality = quality,
                        sessionPresent = !musicU.isNullOrBlank(),
                    )
                    val result = try {
                        songApi.getUrl(
                            task.songId.toString(),
                            quality,
                            cookies(musicU.orEmpty()),
                        )
                    } finally {
                        sourceTraceContext.remove()
                    }
                    currentCoroutineContext().ensureActive()
                    val observation = sourceObservation.get()
                    lastFailure = observation?.failure ?: sourceRequestFailure.get()
                    val candidate = if (observation?.failure == null) result?.url?.trim().orEmpty() else ""
                    if (candidate.isEmpty()) {
                        if (lastFailure == null) lastFailure = SourceResolutionFailure.Unknown
                        recordDiagnostic(
                            task,
                            DownloadDiagnosticStage.SOURCE_RESOLVING,
                            elapsedSince(resolveStarted),
                            DownloadDiagnosticOutcome.FAILED,
                            lastFailure,
                            quality,
                            !musicU.isNullOrBlank(),
                        )
                        continue
                    }

                    val validationStarted = System.nanoTime()
                    val urlMetadata = audioUrlPolicy.inspect(candidate, AudioUrlOrigin.NETEASE_SOURCE_API)
                    try {
                        audioUrlPolicy.prepare(candidate, AudioUrlOrigin.NETEASE_SOURCE_API)
                    } catch (policyError: AudioUrlPolicyException) {
                        recordDiagnostic(
                            task,
                            DownloadDiagnosticStage.URL_VALIDATION,
                            elapsedSince(validationStarted),
                            DownloadDiagnosticOutcome.FAILED,
                            SourceResolutionFailure.AudioUrlPolicy(policyError.failure, urlMetadata.validationRule),
                            quality,
                            !musicU.isNullOrBlank(),
                            urlMetadata,
                        )
                        throw SourceResolutionException(
                            SourceResolutionFailure.AudioUrlPolicy(policyError.failure, urlMetadata.validationRule),
                            actualQuality = result?.level?.takeIf { it in NeteaseApi.QUALITY_MAP },
                        )
                    }
                    recordDiagnostic(
                        task,
                        DownloadDiagnosticStage.URL_VALIDATION,
                        elapsedSince(validationStarted),
                        DownloadDiagnosticOutcome.SUCCEEDED,
                        attemptQuality = quality,
                        sessionPresent = !musicU.isNullOrBlank(),
                        urlMetadata = urlMetadata,
                    )
                    recordDiagnostic(
                        task,
                        DownloadDiagnosticStage.SOURCE_RESOLVING,
                        elapsedSince(resolveStarted),
                        DownloadDiagnosticOutcome.SUCCEEDED,
                        attemptQuality = quality,
                        sessionPresent = !musicU.isNullOrBlank(),
                    )
                    return@withContext ResolvedSongUrl(
                        candidate,
                        result?.type,
                        result?.level ?: quality,
                        AudioUrlOrigin.NETEASE_SOURCE_API,
                    )
                }

                if (musicU.isNullOrBlank()) {
                    recordDiagnostic(
                        task,
                        DownloadDiagnosticStage.SOURCE_RESOLVING,
                        elapsedSince(resolveStarted),
                        DownloadDiagnosticOutcome.FAILED,
                        SourceResolutionFailure.SourceUnavailable,
                        sessionPresent = false,
                    )
                    throw SourceUnavailableException(
                        "No source URL was returned for this task.",
                        authorizationMayBeRequired = true,
                    )
                }

                val sessionStarted = System.nanoTime()
                recordDiagnostic(
                    task,
                    DownloadDiagnosticStage.SESSION_CHECK,
                    0L,
                    DownloadDiagnosticOutcome.STARTED,
                    sessionPresent = true,
                )
                when (sessionValidatorOverride?.invoke(musicU) ?: validateSession(musicU)) {
                    SessionValidation.EXPIRED -> {
                        recordDiagnostic(
                            task,
                            DownloadDiagnosticStage.SESSION_CHECK,
                            elapsedSince(sessionStarted),
                            DownloadDiagnosticOutcome.FAILED,
                            SourceResolutionFailure.SessionExpired,
                            sessionPresent = true,
                        )
                        throw SessionExpiredException()
                    }
                    SessionValidation.TEMPORARY_FAILURE -> {
                        recordDiagnostic(
                            task,
                            DownloadDiagnosticStage.SESSION_CHECK,
                            elapsedSince(sessionStarted),
                            DownloadDiagnosticOutcome.FAILED,
                            SourceResolutionFailure.SessionCheckUnavailable,
                            sessionPresent = true,
                        )
                        throw SourceResolutionException(SourceResolutionFailure.SessionCheckUnavailable)
                    }
                    SessionValidation.VALID -> recordDiagnostic(
                        task,
                        DownloadDiagnosticStage.SESSION_CHECK,
                        elapsedSince(sessionStarted),
                        DownloadDiagnosticOutcome.SUCCEEDED,
                        sessionPresent = true,
                    )
                }

                val failure = lastFailure ?: SourceResolutionFailure.SourceUnavailable
                recordDiagnostic(
                    task,
                    DownloadDiagnosticStage.SOURCE_RESOLVING,
                    elapsedSince(resolveStarted),
                    DownloadDiagnosticOutcome.FAILED,
                    failure,
                    sessionPresent = true,
                )
                throw SourceResolutionException(failure)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } finally {
                sourceTraceContext.remove()
                sourceObservation.remove()
                sourceRequestFailure.remove()
                resolvingTaskId.remove()
                resolvingCalls.remove(task.id)
            }
        }

    private fun recordDiagnostic(
        task: DownloadTask,
        stage: DownloadDiagnosticStage,
        elapsedMillis: Long,
        outcome: DownloadDiagnosticOutcome,
        error: SourceResolutionFailure? = null,
        attemptQuality: String? = null,
        sessionPresent: Boolean,
        urlMetadata: AudioUrlMetadata? = null,
    ) {
        diagnostics?.record(
            DownloadDiagnosticEvent(
                taskId = task.id,
                songId = task.songId,
                requestedQuality = task.requestedQuality,
                stage = stage,
                elapsedMillis = elapsedMillis,
                outcome = outcome,
                error = error,
                attemptQuality = attemptQuality,
                sessionPresent = sessionPresent,
                urlMetadata = urlMetadata,
            ),
        )
    }

    private fun elapsedSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    override fun cancel(taskId: String) {
        resolvingCalls.remove(taskId)?.cancel()
    }

    private fun cookies(musicU: String): Map<String, String> {
        return desktopSongApiCookies(musicU)
    }

}
