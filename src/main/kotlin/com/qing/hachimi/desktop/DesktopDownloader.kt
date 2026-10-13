package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Resumable streaming transport. Production requests are HTTPS-only and never send cookies to a CDN. */
class DesktopDownloader internal constructor(
    private val client: OkHttpClient = defaultClient(),
    internal val allowHttpForLoopbackTests: Boolean = false,
    private val diagnostics: DownloadDiagnosticsStore? = null,
    private val audioUrlPolicy: AudioUrlPolicy = AudioUrlPolicy(),
    private val networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(),
) : DownloadTransport {
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val targetLocks = ConcurrentHashMap<Path, Mutex>()

    override fun partialPath(target: Path): Path = target.resolveSibling("${target.fileName}.hachimi.part")

    internal fun metadataPath(target: Path): Path =
        target.resolveSibling("${target.fileName}.hachimi.part.json")

    override fun cancel(taskId: String) {
        activeCalls[taskId]?.cancel()
    }

    override fun deletePartial(target: Path) {
        Files.deleteIfExists(partialPath(target))
        Files.deleteIfExists(metadataPath(target))
    }

    override suspend fun download(
        taskId: String,
        target: Path,
        initialSource: ResolvedSongUrl,
        resolveAgain: suspend () -> ResolvedSongUrl,
        onProgress: suspend (DownloadProgressUpdate) -> Unit,
    ): DownloadTransferResult = withContext(Dispatchers.IO) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        Files.createDirectories(normalizedTarget.parent)
        val lock = targetLocks.computeIfAbsent(normalizedTarget) { Mutex() }
        lock.withLock {
            val startedAt = System.nanoTime()
            diagnostics?.recordForActiveTask(
                taskId,
                DownloadDiagnosticStage.HTTP_TRANSFER,
                0L,
                DownloadDiagnosticOutcome.STARTED,
                attemptQuality = initialSource.actualQuality,
            )
            try {
                val result = performDownload(taskId, normalizedTarget, initialSource, resolveAgain, onProgress)
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.HTTP_TRANSFER,
                    elapsedSince(startedAt),
                    DownloadDiagnosticOutcome.SUCCEEDED,
                    attemptQuality = result.actualQuality,
                )
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.HTTP_TRANSFER,
                    elapsedSince(startedAt),
                    DownloadDiagnosticOutcome.FAILED,
                    SourceResolutionFailure.Cancelled,
                    initialSource.actualQuality,
                )
                throw cancelled
            } catch (error: Exception) {
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.HTTP_TRANSFER,
                    elapsedSince(startedAt),
                    DownloadDiagnosticOutcome.FAILED,
                    NetworkFailureClassifier.classify(error),
                    initialSource.actualQuality,
                )
                throw error
            }
        }
    }

    /** Kept as a short transition adapter for the original M0 screen. */
    suspend fun download(
        song: SongHit,
        url: String,
        type: String?,
        folder: Path,
        onProgress: suspend (read: Long, total: Long?) -> Unit,
    ): Path {
        val target = FileNamePolicy.destination(folder, song, type)
        val source = ResolvedSongUrl(url, type, "standard")
        download("legacy-${song.id}", target, source, { source }) { progress ->
            onProgress(progress.bytesDownloaded, progress.totalBytes)
        }
        return target
    }

    private suspend fun performDownload(
        taskId: String,
        target: Path,
        initialSource: ResolvedSongUrl,
        resolveAgain: suspend () -> ResolvedSongUrl,
        onProgress: suspend (DownloadProgressUpdate) -> Unit,
    ): DownloadTransferResult {
        val part = partialPath(target)
        val metaFile = metadataPath(target)
        var metadata = readMetadata(metaFile)
        var offset = if (Files.isRegularFile(part)) Files.size(part) else 0L
        if (offset > 0L && metadata?.ifRangeValue() == null) {
            resetPartial(part, metaFile)
            metadata = null
            offset = 0L
        }

        var source = initialSource
        var refreshCount = 0
        var restartCount = 0
        var probeApprovedUrl: String? = null
        val rangePattern = Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$")
        val unsatisfiedRangePattern = Regex("(?i)^bytes\\s+\\*/(\\d+)$")

        while (true) {
            currentCoroutineContext().ensureActive()
            val requestedOffset = offset
            val validationStarted = System.nanoTime()
            val preparedSource = try {
                audioUrlPolicy.prepare(source.url, source.origin, allowHttpForLoopbackTests)
            } catch (error: Exception) {
                val failure = (error as? AudioUrlPolicyException)?.failure
                    ?.let(SourceResolutionFailure::AudioUrlPolicy)
                    ?: SourceResolutionFailure.UnsafeUrl
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.URL_VALIDATION,
                    elapsedSince(validationStarted),
                    DownloadDiagnosticOutcome.FAILED,
                    failure,
                    source.actualQuality,
                )
                throw error
            }
            if (preparedSource.upgradedFromHttp && probeApprovedUrl != preparedSource.url) {
                try {
                    probeHttpsSource(taskId, preparedSource, source.origin, source.actualQuality)
                    probeApprovedUrl = preparedSource.url
                } catch (expired: AudioUrlPolicyException) {
                    diagnostics?.recordForActiveTask(
                        taskId,
                        DownloadDiagnosticStage.URL_VALIDATION,
                        elapsedSince(validationStarted),
                        DownloadDiagnosticOutcome.FAILED,
                        SourceResolutionFailure.AudioUrlPolicy(expired.failure),
                        source.actualQuality,
                        urlMetadata = preparedSource.originalMetadata,
                    )
                    if (expired.failure == AudioUrlFailure.AUDIO_SOURCE_EXPIRED && refreshCount == 0) {
                        source = resolveAgain()
                        probeApprovedUrl = null
                        refreshCount++
                        continue
                    }
                    throw expired
                }
            }
            val request = try {
                buildRequest(preparedSource, requestedOffset, metadata)
            } catch (error: Exception) {
                val failure = (error as? AudioUrlPolicyException)?.failure
                    ?.let(SourceResolutionFailure::AudioUrlPolicy)
                    ?: SourceResolutionFailure.UnsafeUrl
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.URL_VALIDATION,
                    elapsedSince(validationStarted),
                    DownloadDiagnosticOutcome.FAILED,
                    failure,
                    source.actualQuality,
                    urlMetadata = preparedSource.originalMetadata,
                )
                throw error
            }
            val response = executeFollowingRedirects(
                taskId,
                request,
                source.origin,
                preparedSource,
                source.actualQuality,
            )
            try {
                val code = response.code
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.HTTP_TRANSFER,
                    0L,
                    DownloadDiagnosticOutcome.RESPONSE,
                    error = if (code in 200..299) null else SourceResolutionFailure.CdnHttpError(code),
                    attemptQuality = source.actualQuality,
                    httpStatus = code,
                )
                if (code in setOf(401, 403, 404, 410) && refreshCount == 0) {
                    response.close()
                    source = resolveAgain()
                    probeApprovedUrl = null
                    refreshCount++
                    continue
                }
                if (code in setOf(401, 403, 404, 410)) {
                    throw AudioUrlPolicyException(AudioUrlFailure.AUDIO_SOURCE_EXPIRED)
                }

                if (code == 416 && requestedOffset > 0L) {
                    val total = response.header("Content-Range")
                        ?.let(unsatisfiedRangePattern::matchEntire)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val responseEtag = response.header("ETag")
                    val responseLastModified = response.header("Last-Modified")
                    val sameObject = metadata != null && metadata.matches(responseEtag, responseLastModified)
                    if (total != null && total == requestedOffset && Files.size(part) == total && sameObject) {
                        return commit(
                            taskId,
                            target,
                            part,
                            metaFile,
                            total,
                            metadata.etag,
                            metadata.lastModified,
                            source.actualQuality,
                        )
                    }
                    if (restartCount++ == 0) {
                        resetPartial(part, metaFile)
                        metadata = null
                        offset = 0L
                        continue
                    }
                    throw DownloadHttpException(416)
                }

                if (code == 200) {
                    // A server that ignores Range must never append the full object to old bytes.
                    if (requestedOffset > 0L) {
                        resetPartial(part, metaFile)
                        metadata = null
                        offset = 0L
                    }
                    return streamAndCommit(
                        taskId = taskId,
                        response = response,
                        target = target,
                        part = part,
                        metaFile = metaFile,
                        append = false,
                        startOffset = 0L,
                        expectedResponseBytes = response.body.contentLength().takeIf { it >= 0L },
                        expectedTotalBytes = response.body.contentLength().takeIf { it >= 0L },
                        source = source,
                        onProgress = onProgress,
                    )
                }

                if (code == 206) {
                    if (requestedOffset <= 0L) throw InvalidDownloadException("未请求续传却收到 206 Partial Content")
                    val contentRange = response.header("Content-Range")
                        ?.let(rangePattern::matchEntire)
                        ?: throw InvalidDownloadException("续传响应缺少有效 Content-Range")
                    val start = contentRange.groupValues[1].toLong()
                    val end = contentRange.groupValues[2].toLong()
                    val total = contentRange.groupValues[3].toLong()
                    val expectedResponseBytes = end - start + 1L
                    val responseLength = response.body.contentLength()
                    if (start != requestedOffset || end < start || total <= end ||
                        (responseLength >= 0L && responseLength != expectedResponseBytes)
                    ) {
                        throw InvalidDownloadException("续传响应的范围或长度不匹配")
                    }
                    val responseEtag = response.header("ETag")
                    val responseLastModified = response.header("Last-Modified")
                    val sameObject = metadata?.matches(responseEtag, responseLastModified) == true
                    val sameLength = metadata?.totalBytes == null || metadata.totalBytes == total
                    if (!sameObject || !sameLength) {
                        if (restartCount++ == 0) {
                            resetPartial(part, metaFile)
                            metadata = null
                            offset = 0L
                            continue
                        }
                        throw InvalidDownloadException("续传对象已变化，无法安全追加")
                    }
                    return streamAndCommit(
                        taskId = taskId,
                        response = response,
                        target = target,
                        part = part,
                        metaFile = metaFile,
                        append = true,
                        startOffset = requestedOffset,
                        expectedResponseBytes = expectedResponseBytes,
                        expectedTotalBytes = total,
                        source = source,
                        onProgress = onProgress,
                    )
                }

                throw DownloadHttpException(code)
            } catch (invalid: InvalidDownloadException) {
                if (invalid.message?.let { "不是音频" in it || "空音频" in it } == true) {
                    resetPartial(part, metaFile)
                }
                throw invalid
            } finally {
                response.close()
                activeCalls.remove(taskId)
            }
        }
    }

    private suspend fun streamAndCommit(
        taskId: String,
        response: Response,
        target: Path,
        part: Path,
        metaFile: Path,
        append: Boolean,
        startOffset: Long,
        expectedResponseBytes: Long?,
        expectedTotalBytes: Long?,
        source: ResolvedSongUrl,
        onProgress: suspend (DownloadProgressUpdate) -> Unit,
    ): DownloadTransferResult {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (isRejectedContentType(contentType)) {
            resetPartial(part, metaFile)
            throw InvalidDownloadException("服务器返回的不是音频内容")
        }
        if (append && Files.size(part) != startOffset) {
            throw IOException("续传临时文件大小与请求偏移不匹配")
        }

        val etag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        writeMetadata(
            metaFile,
            ResumeMetadata(etag, lastModified, expectedTotalBytes, contentType),
        )

        var bytesWrittenThisResponse = 0L
        var bytesDownloaded = startOffset
        var lastReportBytes = bytesDownloaded
        var lastReportNanos = System.nanoTime()
        try {
            response.body.byteStream().buffered().use { input ->
                FileOutputStream(part.toFile(), append).use { output ->
                    val prefix = ByteArray(512)
                    var prefixSize = 0
                    while (prefixSize < prefix.size) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(prefix, prefixSize, prefix.size - prefixSize)
                        if (read < 0) break
                        prefixSize += read
                    }
                    if (prefixSize == 0) throw InvalidDownloadException("服务器返回空音频")
                    if (looksLikeErrorDocument(prefix, prefixSize)) {
                        throw InvalidDownloadException("服务器返回的不是音频内容")
                    }
                    output.write(prefix, 0, prefixSize)
                    bytesWrittenThisResponse += prefixSize
                    bytesDownloaded += prefixSize
                    reportProgress(bytesDownloaded, expectedTotalBytes, lastReportBytes, lastReportNanos, etag, lastModified, onProgress)
                    lastReportBytes = bytesDownloaded
                    lastReportNanos = System.nanoTime()

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesWrittenThisResponse += read
                        bytesDownloaded += read
                        val now = System.nanoTime()
                        if (now - lastReportNanos >= PROGRESS_INTERVAL_NANOS) {
                            val elapsed = (now - lastReportNanos).coerceAtLeast(1L)
                            val speed = ((bytesDownloaded - lastReportBytes) * 1_000_000_000L / elapsed)
                                .coerceAtLeast(0L)
                            onProgress(
                                DownloadProgressUpdate(bytesDownloaded, expectedTotalBytes, speed, etag, lastModified),
                            )
                            lastReportBytes = bytesDownloaded
                            lastReportNanos = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep transport failures and their validators so an explicit retry can continue safely.
            currentCoroutineContext().ensureActive()
            throw error
        }

        if (expectedResponseBytes != null && bytesWrittenThisResponse != expectedResponseBytes) {
            throw IOException("续传响应长度不完整，临时数据已保留")
        }
        val actualSize = Files.size(part)
        if (expectedTotalBytes != null && actualSize != expectedTotalBytes) {
            throw IOException("音频长度未完整接收，临时数据已保留")
        }
        onProgress(DownloadProgressUpdate(actualSize, expectedTotalBytes, 0L, etag, lastModified))
        return commit(taskId, target, part, metaFile, actualSize, etag, lastModified, source.actualQuality)
    }

    private suspend fun reportProgress(
        bytesDownloaded: Long,
        totalBytes: Long?,
        lastBytes: Long,
        lastNanos: Long,
        etag: String?,
        lastModified: String?,
        onProgress: suspend (DownloadProgressUpdate) -> Unit,
    ) {
        val now = System.nanoTime()
        val speed = ((bytesDownloaded - lastBytes) * 1_000_000_000L / (now - lastNanos).coerceAtLeast(1L))
            .coerceAtLeast(0L)
        onProgress(DownloadProgressUpdate(bytesDownloaded, totalBytes, speed, etag, lastModified))
    }

    private fun commit(
        taskId: String,
        target: Path,
        part: Path,
        metaFile: Path,
        bytes: Long,
        etag: String?,
        lastModified: String?,
        actualQuality: String,
    ): DownloadTransferResult {
        val startedAt = System.nanoTime()
        try {
            try {
                // Hard-link creation is atomic and cannot replace a file that appeared concurrently.
                Files.createLink(target, part)
                Files.deleteIfExists(part)
            } catch (_: UnsupportedOperationException) {
                Files.move(part, target)
            } catch (error: IOException) {
                if (Files.exists(target)) throw FileAlreadyExistsException(target.toString())
                Files.move(part, target)
            }
            Files.deleteIfExists(metaFile)
            activeCalls.remove(taskId)
        } catch (error: Exception) {
            diagnostics?.recordForActiveTask(
                taskId,
                DownloadDiagnosticStage.FILE_COMMIT,
                elapsedSince(startedAt),
                DownloadDiagnosticOutcome.FAILED,
                SourceResolutionFailure.FileCommitFailure,
                actualQuality,
            )
            throw error
        }
        diagnostics?.recordForActiveTask(
            taskId,
            DownloadDiagnosticStage.FILE_COMMIT,
            elapsedSince(startedAt),
            DownloadDiagnosticOutcome.SUCCEEDED,
            attemptQuality = actualQuality,
        )
        return DownloadTransferResult(bytes, bytes, etag, lastModified, actualQuality)
    }
    private fun probeHttpsSource(
        taskId: String,
        prepared: PreparedAudioUrl,
        origin: AudioUrlOrigin,
        actualQuality: String,
    ) {
        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url(prepared.url)
            .header("User-Agent", DesktopProductIdentity.USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .header("Range", "bytes=0-0")
            .get()
            .build()
        val response = executeFollowingRedirects(taskId, request, origin, prepared, actualQuality)
        try {
            val code = response.code
            diagnostics?.recordForActiveTask(
                taskId,
                DownloadDiagnosticStage.HTTP_TRANSFER,
                elapsedSince(startedAt),
                DownloadDiagnosticOutcome.RESPONSE,
                    error = if (code in 200..299) null else SourceResolutionFailure.CdnHttpError(code),
                attemptQuality = actualQuality,
                httpStatus = code,
            )
            if (code in setOf(401, 403, 404, 410)) {
                throw AudioUrlPolicyException(AudioUrlFailure.AUDIO_SOURCE_EXPIRED)
            }
            if (code !in setOf(200, 206)) {
                throw AudioUrlPolicyException(AudioUrlFailure.HTTP_TRANSFER_FAILED)
            }
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (isRejectedContentType(contentType)) {
                throw AudioUrlPolicyException(AudioUrlFailure.HTTP_TRANSFER_FAILED)
            }
            val sample = response.body.byteStream().use { it.readNBytes(MAX_PROBE_BYTES) }
            if (sample.isEmpty() || looksLikeErrorDocument(sample, sample.size)) {
                throw AudioUrlPolicyException(AudioUrlFailure.HTTP_TRANSFER_FAILED)
            }
            if (code == 206) {
                val range = response.header("Content-Range")
                val total = range?.let(PROBE_CONTENT_RANGE::matchEntire)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (total == null || total <= 0L || sample.size != 1 ||
                    response.body.contentLength() >= 0L && response.body.contentLength() != 1L
                ) {
                    throw AudioUrlPolicyException(AudioUrlFailure.HTTPS_UPGRADE_UNAVAILABLE)
                }
            }
        } catch (_: SSLException) {
            throw AudioUrlPolicyException(AudioUrlFailure.TLS_VALIDATION_FAILED)
        } finally {
            response.close()
        }
    }

    private fun buildRequest(prepared: PreparedAudioUrl, offset: Long, metadata: ResumeMetadata?): Request {
        val uri = runCatching { URI(prepared.url) }
            .getOrElse { throw AudioUrlPolicyException(AudioUrlFailure.INVALID_URL_FORMAT) }
        val validProtocol = uri.scheme.equals("https", ignoreCase = true) ||
            prepared.loopbackTestOnly && uri.scheme.equals("http", ignoreCase = true)
        if (!validProtocol || uri.userInfo != null) {
            throw AudioUrlPolicyException(AudioUrlFailure.UNTRUSTED_AUDIO_HOST)
        }
        val builder = Request.Builder()
            .url(prepared.url)
            .header("User-Agent", DesktopProductIdentity.USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .get()
        if (offset > 0L) {
            val validator = metadata?.ifRangeValue()
                ?: throw InvalidDownloadException("缺少续传校验信息")
            builder.header("Range", "bytes=$offset-")
            builder.header("If-Range", validator)
        }
        return builder.build()
    }

    private fun executeFollowingRedirects(
        taskId: String,
        initial: Request,
        origin: AudioUrlOrigin,
        initialSource: PreparedAudioUrl,
        attemptQuality: String,
    ): Response {
        var request = initial
        val requiredHost = initialSource.takeIf { it.upgradedFromHttp }?.originalMetadata?.host
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val validationStarted = System.nanoTime()
            val currentUri = URI(request.url.toString())
            val currentMetadata = audioUrlPolicy.inspect(request.url.toString(), origin)
            val routedClient = try {
                audioUrlPolicy.prepareRedirect(
                    request.url.toString(),
                    origin,
                    allowHttpForLoopbackTests,
                    requiredHost,
                )
                val selectedRoute = networkFactory.routeFor(currentUri)
                if (selectedRoute != null && !audioUrlPolicy.isTrustedAudioProxyHost(currentMetadata.host)) {
                    throw AudioUrlPolicyException(AudioUrlFailure.UNTRUSTED_AUDIO_HOST)
                }
                audioUrlPolicy.networkClientFor(
                    client,
                    currentUri,
                    allowHttpForLoopbackTests,
                    networkFactory,
                    selectedRoute,
                )
            } catch (error: AudioUrlPolicyException) {
                diagnostics?.recordForActiveTask(
                    taskId,
                    DownloadDiagnosticStage.URL_VALIDATION,
                    elapsedSince(validationStarted),
                    DownloadDiagnosticOutcome.FAILED,
                    NetworkFailureClassifier.classify(error),
                    attemptQuality = attemptQuality,
                    urlMetadata = currentMetadata,
                )
                throw error
            }
            diagnostics?.recordForActiveTask(
                taskId,
                DownloadDiagnosticStage.URL_VALIDATION,
                elapsedSince(validationStarted),
                DownloadDiagnosticOutcome.SUCCEEDED,
                attemptQuality = attemptQuality,
                urlMetadata = currentMetadata,
            )
            val call = routedClient.newCall(request)
            activeCalls[taskId] = call
            val response = try {
                call.execute()
            } catch (timeout: SocketTimeoutException) {
                activeCalls.remove(taskId, call)
                throw timeout
            } catch (_: SSLException) {
                activeCalls.remove(taskId, call)
                throw AudioUrlPolicyException(AudioUrlFailure.TLS_VALIDATION_FAILED)
            } catch (error: IOException) {
                activeCalls.remove(taskId, call)
                throw error
            }
            val location = response.header("Location")
            if (response.code !in REDIRECT_CODES) return response
            response.close()
            activeCalls.remove(taskId, call)
            if (redirectIndex == MAX_REDIRECTS || location.isNullOrBlank()) {
                throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED)
            }
            val next = runCatching { URI(request.url.toString()).resolve(location) }
                .getOrElse { throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED) }
            val nextPrepared = audioUrlPolicy.prepareRedirect(
                next.toASCIIString(),
                origin,
                allowHttpForLoopbackTests,
                requiredHost,
            )
            request = request.newBuilder().url(nextPrepared.url).build()
        }
        throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED)
    }

    private fun transferFailure(error: Exception): SourceResolutionFailure =
        NetworkFailureClassifier.classify(error)

    private fun elapsedSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun isRejectedContentType(contentType: String): Boolean {
        val mime = contentType.lowercase()
        return mime.startsWith("text/") || mime.contains("html") || mime.contains("json") ||
            mime.contains("xml") || mime.startsWith("image/") || mime.startsWith("video/")
    }

    private fun looksLikeErrorDocument(bytes: ByteArray, size: Int): Boolean {
        val prefix = bytes.copyOfRange(0, size).toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .take(128)
            .lowercase()
        return prefix.startsWith("<html") || prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<?xml") || prefix.startsWith("{\"code\"") || prefix.startsWith("{\"error\"")
    }

    private fun resetPartial(part: Path, metaFile: Path) {
        Files.deleteIfExists(part)
        Files.deleteIfExists(metaFile)
    }

    private fun readMetadata(path: Path): ResumeMetadata? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val json = JSONObject(Files.readString(path))
            ResumeMetadata(
                etag = json.optNullableString("etag"),
                lastModified = json.optNullableString("lastModified"),
                totalBytes = if (json.isNull("totalBytes")) null else json.optLong("totalBytes"),
                contentType = json.optString("contentType").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun writeMetadata(path: Path, metadata: ResumeMetadata) {
        val parent = path.toAbsolutePath().parent
        val temp = Files.createTempFile(parent, ".hachimi-resume-", ".tmp")
        try {
            val json = JSONObject()
                .put("etag", metadata.etag)
                .put("lastModified", metadata.lastModified)
                .put("totalBytes", metadata.totalBytes)
                .put("contentType", metadata.contentType)
            Files.writeString(temp, json.toString(), StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private data class ResumeMetadata(
        val etag: String?,
        val lastModified: String?,
        val totalBytes: Long?,
        val contentType: String?,
    ) {
        fun ifRangeValue(): String? = etag
            ?.takeUnless { it.startsWith("W/", ignoreCase = true) }
            ?: lastModified

        fun matches(responseEtag: String?, responseLastModified: String?): Boolean {
            val validator = ifRangeValue() ?: return false
            return if (validator == etag) responseEtag == etag else responseLastModified == lastModified
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_PROBE_BYTES = 16 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private val PROBE_CONTENT_RANGE = Regex("(?i)^bytes\\s+0-0/(\\d+)$")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
