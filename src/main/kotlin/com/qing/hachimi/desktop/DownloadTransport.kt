package com.qing.hachimi.desktop

import java.nio.file.Path

data class DownloadProgressUpdate(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val etag: String?,
    val lastModified: String?,
)

data class DownloadTransferResult(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val actualQuality: String,
)

fun interface SongSourceResolver {
    suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl

    fun cancel(taskId: String) = Unit
}

interface DownloadTransport {
    suspend fun download(
        taskId: String,
        target: Path,
        initialSource: ResolvedSongUrl,
        resolveAgain: suspend () -> ResolvedSongUrl,
        onProgress: suspend (DownloadProgressUpdate) -> Unit,
    ): DownloadTransferResult

    fun cancel(taskId: String)

    fun partialPath(target: Path): Path = target.resolveSibling("${target.fileName}.hachimi.part")

    fun deletePartial(target: Path)
}

class SourceUnavailableException(
    message: String,
    val authorizationMayBeRequired: Boolean,
) : java.io.IOException(message)

/** A typed source failure with a fixed safe summary; server text is never exposed to the UI. */
class SourceResolutionException(
    val failure: SourceResolutionFailure,
    actualQuality: String? = null,
) : java.io.IOException(sourceFailureSummary(failure)) {
    val actualQuality: String? = actualQuality?.takeIf { it in setOf("standard", "exhigh", "lossless", "hires", "sky") }
}

private fun sourceFailureSummary(failure: SourceResolutionFailure): String = when (failure) {
    is SourceResolutionFailure.HttpStatus -> "音源接口返回 HTTP ${failure.statusCode}。"
    is SourceResolutionFailure.ApiBusinessCode -> "音源接口返回业务码 ${failure.code ?: "未知"}。"
    is SourceResolutionFailure.DataItemCode -> "歌曲音源响应码为 ${failure.code ?: "未知"}。"
    SourceResolutionFailure.InvalidJson -> "音源接口返回了无法解析的数据。"
    SourceResolutionFailure.DataMissing -> "音源接口没有返回歌曲数据。"
    SourceResolutionFailure.UrlMissing -> "服务端没有返回可用的音源地址。"
    SourceResolutionFailure.RequestTimeout -> "音源请求超时。"
    SourceResolutionFailure.DnsResolutionFailed -> "DNS 无法解析音源主机。"
    SourceResolutionFailure.ProxyOrRouteUnavailable -> "网络代理或路由当前不可用。"
    is SourceResolutionFailure.CdnHttpError -> "音源 CDN 返回 HTTP ${failure.statusCode}。"
    SourceResolutionFailure.TlsFailure -> "音源连接的 TLS 安全校验失败。"
    SourceResolutionFailure.ConnectionFailure -> "无法连接音源服务。"
    SourceResolutionFailure.SessionExpired -> "登录会话已过期，请重新扫码登录。"
    SourceResolutionFailure.SessionAbsent -> "当前没有登录会话。"
    SourceResolutionFailure.SessionCheckUnavailable -> "暂时无法验证登录状态。"
    SourceResolutionFailure.UnsafeUrl -> "服务端返回的音源地址未通过 HTTPS 安全校验。"
    is SourceResolutionFailure.AudioUrlPolicy -> AudioUrlPolicyException(failure.failure).message.orEmpty()
    SourceResolutionFailure.SourceUnavailable -> "服务端没有提供当前任务可用的音源。"
    SourceResolutionFailure.TransferFailure -> "音源已解析，但文件传输失败。"
    SourceResolutionFailure.InvalidAudio -> "下载响应不是可识别的音频文件。"
    SourceResolutionFailure.FileCommitFailure -> "音频已下载，但无法安全写入目标文件。"
    SourceResolutionFailure.StorageFailure -> "本地文件操作失败。"
    SourceResolutionFailure.Cancelled -> "下载已取消。"
    SourceResolutionFailure.Unknown -> "音源解析遇到未分类错误，可查看本机诊断记录。"
}

class SessionExpiredException : java.io.IOException("登录状态已过期")

class DownloadHttpException(val statusCode: Int) : java.io.IOException("下载请求失败（HTTP $statusCode）")

class InvalidDownloadException(message: String) : java.io.IOException(message)
