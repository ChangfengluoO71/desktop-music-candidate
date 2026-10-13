package com.qing.hachimi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.Interceptor

enum class DownloadDiagnosticStage {
    SESSION_CHECK,
    SONG_SELECTED,
    SOURCE_RESOLVING,
    SOURCE_RESPONSE,
    URL_VALIDATION,
    HTTP_TRANSFER,
    FILE_COMMIT,
}

enum class DownloadDiagnosticOutcome { STARTED, RESPONSE, SUCCEEDED, FAILED }

/** Typed failures contain no server message, request URL, or exception text. */
sealed interface SourceResolutionFailure {
    data class HttpStatus(val statusCode: Int) : SourceResolutionFailure
    data class ApiBusinessCode(val code: String?) : SourceResolutionFailure
    data class DataItemCode(val code: String?) : SourceResolutionFailure
    data object InvalidJson : SourceResolutionFailure
    data object DataMissing : SourceResolutionFailure
    data object UrlMissing : SourceResolutionFailure
    data object RequestTimeout : SourceResolutionFailure
    data object DnsResolutionFailed : SourceResolutionFailure
    data object ProxyOrRouteUnavailable : SourceResolutionFailure
    data class CdnHttpError(val statusCode: Int) : SourceResolutionFailure
    data object TlsFailure : SourceResolutionFailure
    data object ConnectionFailure : SourceResolutionFailure
    data object SessionExpired : SourceResolutionFailure
    data object SessionAbsent : SourceResolutionFailure
    data object SessionCheckUnavailable : SourceResolutionFailure
    data object UnsafeUrl : SourceResolutionFailure
    data class AudioUrlPolicy(
        val failure: AudioUrlFailure,
        val validationRule: AudioUrlValidationRule? = null,
    ) : SourceResolutionFailure
    data object SourceUnavailable : SourceResolutionFailure
    data object TransferFailure : SourceResolutionFailure
    data object InvalidAudio : SourceResolutionFailure
    data object FileCommitFailure : SourceResolutionFailure
    data object StorageFailure : SourceResolutionFailure
    data object Cancelled : SourceResolutionFailure
    data object Unknown : SourceResolutionFailure
}

/** Maps exception types to fixed categories; exception messages and request destinations are ignored. */
internal object NetworkFailureClassifier {
    fun classify(error: Throwable): SourceResolutionFailure = when (error) {
        is SourceResolutionException -> error.failure
        is SessionExpiredException -> SourceResolutionFailure.SessionExpired
        is AudioUrlPolicyException -> SourceResolutionFailure.AudioUrlPolicy(error.failure)
        is DownloadHttpException -> SourceResolutionFailure.CdnHttpError(error.statusCode)
        is SocketTimeoutException -> SourceResolutionFailure.RequestTimeout
        is SSLException -> SourceResolutionFailure.TlsFailure
        is UnknownHostException -> SourceResolutionFailure.DnsResolutionFailed
        is NoRouteToHostException -> SourceResolutionFailure.ProxyOrRouteUnavailable
        is ConnectException -> SourceResolutionFailure.ConnectionFailure
        is InvalidDownloadException -> SourceResolutionFailure.InvalidAudio
        is FileAlreadyExistsException -> SourceResolutionFailure.StorageFailure
        is IOException -> SourceResolutionFailure.ConnectionFailure
        else -> SourceResolutionFailure.Unknown
    }

    fun category(error: Throwable): String = category(classify(error))

    fun category(failure: SourceResolutionFailure): String = when (failure) {
        is SourceResolutionFailure.HttpStatus -> "HTTP_STATUS"
        is SourceResolutionFailure.ApiBusinessCode -> "API_BUSINESS_CODE"
        is SourceResolutionFailure.DataItemCode -> "DATA_ITEM_CODE"
        SourceResolutionFailure.InvalidJson -> "JSON_INVALID"
        SourceResolutionFailure.DataMissing -> "DATA_MISSING"
        SourceResolutionFailure.UrlMissing -> "URL_EMPTY"
        SourceResolutionFailure.RequestTimeout -> "TIMEOUT"
        SourceResolutionFailure.DnsResolutionFailed -> "DNS_RESOLUTION_FAILED"
        SourceResolutionFailure.ProxyOrRouteUnavailable -> "PROXY_OR_ROUTE_UNAVAILABLE"
        is SourceResolutionFailure.CdnHttpError -> "CDN_HTTP_ERROR"
        SourceResolutionFailure.TlsFailure -> "TLS_FAILED"
        SourceResolutionFailure.ConnectionFailure -> "CONNECTION_FAILED"
        SourceResolutionFailure.SessionExpired -> "SESSION_EXPIRED"
        SourceResolutionFailure.SessionAbsent -> "SESSION_ABSENT"
        SourceResolutionFailure.SessionCheckUnavailable -> "SESSION_CHECK_UNAVAILABLE"
        SourceResolutionFailure.UnsafeUrl -> "URL_POLICY_REJECTED"
        is SourceResolutionFailure.AudioUrlPolicy -> when (failure.failure) {
            AudioUrlFailure.DNS_RESOLUTION_FAILED -> "DNS_RESOLUTION_FAILED"
            AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED -> "DNS_SYNTHETIC_ADDRESS_SUSPECTED"
            AudioUrlFailure.TLS_VALIDATION_FAILED -> "TLS_FAILED"
            AudioUrlFailure.HTTP_TRANSFER_FAILED -> "CDN_HTTP_ERROR"
            AudioUrlFailure.AUDIO_SOURCE_EXPIRED -> "AUDIO_SOURCE_EXPIRED"
            else -> "URL_POLICY_REJECTED"
        }
        SourceResolutionFailure.SourceUnavailable -> "SOURCE_UNAVAILABLE"
        SourceResolutionFailure.TransferFailure -> "CONNECTION_FAILED"
        SourceResolutionFailure.InvalidAudio -> "INVALID_AUDIO"
        SourceResolutionFailure.FileCommitFailure -> "FILE_COMMIT_FAILED"
        SourceResolutionFailure.StorageFailure -> "STORAGE_FAILURE"
        SourceResolutionFailure.Cancelled -> "CANCELLED"
        SourceResolutionFailure.Unknown -> "UNKNOWN"
    }
}

data class SourceApiObservation(
    val httpStatus: Int,
    val apiBusinessCode: String?,
    val dataItemCode: String?,
    val level: String?,
    val size: Long?,
    val type: String?,
    val urlPresent: Boolean,
    val failure: SourceResolutionFailure?,
    val urlMetadata: AudioUrlMetadata? = null,
)

internal object SourceResponseInspector {
    private const val MAX_RESPONSE_BYTES = 128 * 1024
    private val safeCodePattern = Regex("-?[0-9]{1,12}")
    private val knownLevels = setOf("standard", "exhigh", "lossless", "hires", "sky", "jymaster")
    private val knownTypes = setOf("mp3", "flac", "m4a", "aac", "wav", "ape", "ogg", "alac")

    fun inspect(httpStatus: Int, responseBody: String?): SourceApiObservation {
        val body = responseBody.orEmpty()
        if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return SourceApiObservation(
                httpStatus, null, null, null, null, null, false,
                if (httpStatus !in 200..299) SourceResolutionFailure.HttpStatus(httpStatus)
                else SourceResolutionFailure.InvalidJson,
            )
        }
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return SourceApiObservation(
                httpStatus, null, null, null, null, null, false,
                if (httpStatus !in 200..299) SourceResolutionFailure.HttpStatus(httpStatus)
                else SourceResolutionFailure.InvalidJson,
            )
        }
        val apiCode = safeCode(root.opt("code"))
        val item = root.optJSONArray("data")?.optJSONObject(0)
        val itemCode = item?.takeIf { it.has("code") }?.let { safeCode(it.opt("code")) }
        val level = item?.optString("level")?.takeIf { it in knownLevels }
        val size = item?.takeIf { it.has("size") && !it.isNull("size") }
            ?.optLong("size")?.takeIf { it >= 0L }
        val type = item?.optString("type")?.lowercase()?.takeIf { it in knownTypes }
        val sourceUrl = item?.opt("url")?.let { it as? String }?.takeIf(String::isNotBlank)
        val urlPresent = sourceUrl != null
        val urlMetadata = sourceUrl?.let {
            AudioUrlPolicy().inspect(it, AudioUrlOrigin.NETEASE_SOURCE_API)
        }
        val failure = when {
            httpStatus !in 200..299 -> SourceResolutionFailure.HttpStatus(httpStatus)
            apiCode != "200" -> SourceResolutionFailure.ApiBusinessCode(apiCode)
            item == null -> SourceResolutionFailure.DataMissing
            itemCode != null && itemCode != "200" -> SourceResolutionFailure.DataItemCode(itemCode)
            !urlPresent -> SourceResolutionFailure.UrlMissing
            else -> null
        }
        return SourceApiObservation(httpStatus, apiCode, itemCode, level, size, type, urlPresent, failure, urlMetadata)
    }

    private fun safeCode(value: Any?): String? = value
        ?.toString()
        ?.takeIf { safeCodePattern.matches(it) }
}

internal data class SourceRequestTraceContext(
    val taskId: String,
    val songId: Long,
    val requestedQuality: String,
    val attemptQuality: String,
)

/** Observes only the source API endpoint; it never retains headers or raw response bodies. */
internal class SourceResponseDiagnosticsInterceptor(
    private val diagnostics: DownloadDiagnosticsStore?,
    private val currentContext: () -> SourceRequestTraceContext?,
    private val onRequestFailure: (SourceRequestTraceContext, SourceResolutionFailure, Long) -> Unit = { _, _, _ -> },
    private val onObservation: (SourceRequestTraceContext, SourceApiObservation, Long) -> Unit = { _, _, _ -> },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (request.url.encodedPath != SOURCE_URL_PATH) return chain.proceed(request)
        val context = currentContext() ?: return chain.proceed(request)
        val sessionPresent = hasMusicUSession(request.header("Cookie"))
        val startedAt = System.nanoTime()

        try {
            val response = chain.proceed(request)
            val body = runCatching { response.peekBody(MAX_PEEK_BYTES).string() }.getOrNull()
            val observation = SourceResponseInspector.inspect(response.code, body)
            val elapsed = elapsedMillis(startedAt)
            runCatching {
                diagnostics?.record(
                    DownloadDiagnosticEvent(
                        taskId = context.taskId,
                        songId = context.songId,
                        requestedQuality = context.requestedQuality,
                        stage = DownloadDiagnosticStage.SOURCE_RESPONSE,
                        elapsedMillis = elapsed,
                        outcome = if (observation.failure == null) {
                            DownloadDiagnosticOutcome.SUCCEEDED
                        } else DownloadDiagnosticOutcome.FAILED,
                        error = observation.failure,
                        attemptQuality = context.attemptQuality,
                        httpStatus = observation.httpStatus,
                        apiBusinessCode = observation.apiBusinessCode,
                        dataItemCode = observation.dataItemCode,
                        level = observation.level,
                        size = observation.size,
                        type = observation.type,
                        urlPresent = observation.urlPresent,
                        sessionPresent = sessionPresent,
                        urlMetadata = observation.urlMetadata,
                    ),
                )
                onObservation(context, observation, elapsed)
            }
            return response
        } catch (error: IOException) {
            val elapsed = elapsedMillis(startedAt)
            val failure = classifyRequestFailure(error)
            runCatching {
                diagnostics?.record(
                    DownloadDiagnosticEvent(
                        taskId = context.taskId,
                        songId = context.songId,
                        requestedQuality = context.requestedQuality,
                        stage = DownloadDiagnosticStage.SOURCE_RESPONSE,
                        elapsedMillis = elapsed,
                        outcome = DownloadDiagnosticOutcome.FAILED,
                        error = failure,
                        attemptQuality = context.attemptQuality,
                        sessionPresent = sessionPresent,
                    ),
                )
                onRequestFailure(context, failure, elapsed)
            }
            throw error
        } catch (error: Exception) {
            val elapsed = elapsedMillis(startedAt)
            runCatching {
                diagnostics?.record(
                    DownloadDiagnosticEvent(
                        taskId = context.taskId,
                        songId = context.songId,
                        requestedQuality = context.requestedQuality,
                        stage = DownloadDiagnosticStage.SOURCE_RESPONSE,
                        elapsedMillis = elapsed,
                        outcome = DownloadDiagnosticOutcome.FAILED,
                        error = SourceResolutionFailure.Unknown,
                        attemptQuality = context.attemptQuality,
                        sessionPresent = sessionPresent,
                    ),
                )
                onRequestFailure(context, SourceResolutionFailure.Unknown, elapsed)
            }
            throw error
        }
    }

    private fun hasMusicUSession(cookieHeader: String?): Boolean = cookieHeader
        ?.split(';')
        ?.any { part ->
            val separator = part.indexOf('=')
            separator > 0 && part.substring(0, separator).trim() == "MUSIC_U" &&
                part.substring(separator + 1).isNotBlank()
        } == true

    private fun classifyRequestFailure(error: IOException): SourceResolutionFailure = when (error) {
        is SocketTimeoutException -> SourceResolutionFailure.RequestTimeout
        is SSLException -> SourceResolutionFailure.TlsFailure
        else -> NetworkFailureClassifier.classify(error)
    }

    private fun elapsedMillis(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val SOURCE_URL_PATH = "/eapi/song/enhance/player/url/v1"
        const val MAX_PEEK_BYTES = 128L * 1024L
    }
}

data class DownloadDiagnosticEvent(
    val taskId: String,
    val songId: Long,
    val requestedQuality: String,
    val stage: DownloadDiagnosticStage,
    val elapsedMillis: Long,
    val outcome: DownloadDiagnosticOutcome,
    val error: SourceResolutionFailure? = null,
    val attemptQuality: String? = null,
    val httpStatus: Int? = null,
    val apiBusinessCode: String? = null,
    val dataItemCode: String? = null,
    val level: String? = null,
    val size: Long? = null,
    val type: String? = null,
    val urlPresent: Boolean? = null,
    val sessionPresent: Boolean? = null,
    val urlMetadata: AudioUrlMetadata? = null,
) {
    internal fun toSafeJson(): JSONObject = JSONObject()
        .put("taskId", taskId.take(64).filter { it.isLetterOrDigit() || it in "-_." })
        .put("songId", songId.takeIf { it > 0L })
        .put("requestedQuality", safeQuality(requestedQuality))
        .put("stage", stage.name)
        .put("elapsedMillis", elapsedMillis.coerceAtLeast(0L))
        .put("outcome", outcome.name)
        .put("errorCategory", error?.let(NetworkFailureClassifier::category))
        .put("attemptQuality", attemptQuality?.let(::safeQuality))
        .put("httpStatus", httpStatus?.takeIf { it in 100..599 })
        .put("apiBusinessCode", apiBusinessCode?.let(::safeCode))
        .put("dataItemCode", dataItemCode?.let(::safeCode))
        .put("level", level?.let(::safeQuality))
        .put("size", size?.takeIf { it >= 0L })
        .put("type", type?.let(::safeAudioType))
        .put("urlPresent", urlPresent)
        .put("sessionPresent", sessionPresent)
        .put("urlParseable", urlMetadata?.parseable)
        .put("urlScheme", urlMetadata?.scheme?.name)
        .put("urlHost", urlMetadata?.host?.takeUnless { urlMetadata.ipLiteral }?.takeIf(::safeHost))
        .put("urlExplicitPort", urlMetadata?.explicitPort?.takeIf { it in 1..65535 })
        .put("urlEffectivePort", urlMetadata?.effectivePort?.takeIf { it in 1..65535 })
        .put("urlUserInfoPresent", urlMetadata?.userInfoPresent)
        .put("urlIpLiteral", urlMetadata?.ipLiteral)
        .put("urlValidationRule", urlMetadata?.validationRule?.name)

    private fun safeQuality(value: String): String? = value.takeIf {
        it in setOf("standard", "exhigh", "lossless", "hires", "sky", "jymaster")
    }

    private fun safeCode(value: String): String? = value.takeIf { Regex("-?[0-9]{1,12}").matches(it) }

    private fun safeAudioType(value: String): String? = value.lowercase().takeIf {
        it in setOf("mp3", "flac", "m4a", "aac", "wav", "ape", "ogg", "alac")
    }

    private fun safeHost(value: String): Boolean = value.length <= 253 &&
        value.matches(Regex("(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"))
}

/** Bounded, atomic local diagnostic output. Persisted fields are strictly whitelisted. */
class DownloadDiagnosticsStore(
    private val file: Path,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    private val lock = Any()
    private val events = ArrayDeque<DownloadDiagnosticEvent>()
    private val activeTasks = ConcurrentHashMap<String, DownloadDiagnosticTaskContext>()

    @Volatile
    var enabled: Boolean = false
        private set

    init {
        require(maxEvents in 1..MAX_ALLOWED_EVENTS)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun filePath(): Path = file

    internal fun registerActiveTask(context: DownloadDiagnosticTaskContext) {
        activeTasks[context.taskId] = context
    }

    internal fun clearActiveTask(taskId: String) {
        activeTasks.remove(taskId)
    }

    internal fun recordForActiveTask(
        taskId: String,
        stage: DownloadDiagnosticStage,
        elapsedMillis: Long,
        outcome: DownloadDiagnosticOutcome,
        error: SourceResolutionFailure? = null,
        attemptQuality: String? = null,
        httpStatus: Int? = null,
        urlMetadata: AudioUrlMetadata? = null,
    ) {
        val context = activeTasks[taskId] ?: return
        record(
            DownloadDiagnosticEvent(
                taskId = context.taskId,
                songId = context.songId,
                requestedQuality = context.requestedQuality,
                stage = stage,
                elapsedMillis = elapsedMillis,
                outcome = outcome,
                error = error,
                attemptQuality = attemptQuality,
                httpStatus = httpStatus,
                sessionPresent = context.sessionPresent,
                urlMetadata = urlMetadata,
            ),
        )
    }

    fun record(event: DownloadDiagnosticEvent) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            events.addLast(event)
            while (events.size > maxEvents) events.removeFirst()
            persistLocked()
        }
    }

    fun snapshot(): List<DownloadDiagnosticEvent> = synchronized(lock) { events.toList() }

    private fun persistLocked() {
        val parent = file.toAbsolutePath().parent ?: return
        val temp = parent.resolve("${file.fileName}.tmp")
        try {
            Files.createDirectories(parent)
            val json = JSONArray().apply { events.forEach { put(it.toSafeJson()) } }
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private companion object {
        const val DEFAULT_MAX_EVENTS = 512
        const val MAX_ALLOWED_EVENTS = 2048
    }
}

internal data class DownloadDiagnosticTaskContext(
    val taskId: String,
    val songId: Long,
    val requestedQuality: String,
    val sessionPresent: Boolean,
)
