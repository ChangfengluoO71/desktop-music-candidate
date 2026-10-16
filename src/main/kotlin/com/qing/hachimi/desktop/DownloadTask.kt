package com.qing.hachimi.desktop

import java.nio.file.Path
import java.util.UUID

enum class DownloadTaskStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class DownloadErrorType {
    NETWORK,
    SOURCE_UNAVAILABLE,
    AUTHORIZATION_REQUIRED,
    HTTP,
    INVALID_RESPONSE,
    FILE_EXISTS,
    STORAGE,
    MISSING_FILE,
    UNKNOWN,
}

enum class FileConflictPolicy {
    KEEP_BOTH,
    FAIL_IF_EXISTS,
}

enum class AudioOutputMode { SHARED }

enum class CoverDirectoryMode { BESIDE_MEDIA, CUSTOM_DIRECTORY }

enum class FolderGrouping { NONE, ARTIST_ALBUM, PLAYLIST }

enum class PostProcessingStatus { NOT_REQUESTED, PENDING, PROCESSING, COMPLETED, WARNING, FAILED }

internal const val MAX_AUTOMATIC_DOWNLOAD_RETRIES = 3

data class AudioPostProcessingOptions(
    val downloadLyrics: Boolean = false,
    val saveTranslatedLyrics: Boolean = false,
    val saveRomanizedLyrics: Boolean = false,
    val saveWordLevelLyrics: Boolean = false,
    val embedLyrics: Boolean = false,
    val writeAudioTags: Boolean = false,
    val embedCoverArt: Boolean = false,
) {
    val requested: Boolean get() = downloadLyrics || saveTranslatedLyrics || saveRomanizedLyrics ||
        saveWordLevelLyrics || embedLyrics || writeAudioTags || embedCoverArt
}

data class DesktopSettings(
    val defaultDownloadDirectory: String = Path.of(
        System.getProperty("user.home"), "Music", DesktopProductIdentity.DEFAULT_DOWNLOAD_FOLDER,
    ).toString(),
    val maxConcurrentDownloads: Int = 2,
    val defaultQuality: String = "exhigh",
    val fileConflictPolicy: FileConflictPolicy = FileConflictPolicy.KEEP_BOTH,
    val folderGrouping: FolderGrouping = FolderGrouping.NONE,
    val downloadLyrics: Boolean = false,
    val saveTranslatedLyrics: Boolean = false,
    val saveRomanizedLyrics: Boolean = false,
    val saveWordLevelLyrics: Boolean = false,
    val embedLyrics: Boolean = false,
    val writeAudioTags: Boolean = false,
    val embedCoverArt: Boolean = false,
    val coverDirectoryMode: CoverDirectoryMode = CoverDirectoryMode.BESIDE_MEDIA,
    val customCoverDirectory: String = "",
    val network: DesktopNetworkSettings = DesktopNetworkSettings(),
    val audioOutputMode: AudioOutputMode = AudioOutputMode.SHARED,
    val reducedMotion: Boolean = false,
    val immersiveBackdropSource: ImmersiveBackdropSource = ImmersiveBackdropSource.ALBUM_ART,
    val immersiveBackdropMaterial: ImmersiveBackdropMaterial = ImmersiveBackdropMaterial.SOFT_FOCUS,
    val immersiveBackdropStrength: Int = 50,
    val immersiveBackdropImagePath: String = "",
    val immersiveBackdropSolidColor: String = "#111820",
) {
    init {
        require(maxConcurrentDownloads in 1..8) { "并发数必须在 1 到 8 之间" }
        require(defaultQuality in setOf("standard", "exhigh", "lossless", "hires")) {
            "不支持的默认音质"
        }
        require(defaultDownloadDirectory.isNotBlank()) { "下载目录不能为空" }
        require(immersiveBackdropStrength in 0..100) { "背景材质强度必须在 0 到 100 之间" }
    }

    init {
        require(coverDirectoryMode != CoverDirectoryMode.CUSTOM_DIRECTORY || customCoverDirectory.isNotBlank()) {
            "Custom cover directory cannot be blank"
        }
    }

    init {
        val networkValidationError = network.validationError()
        require(networkValidationError == null) { networkValidationError ?: "网络设置无效" }
    }

    fun postProcessingOptions() = AudioPostProcessingOptions(
        downloadLyrics = downloadLyrics,
        saveTranslatedLyrics = saveTranslatedLyrics,
        saveRomanizedLyrics = saveRomanizedLyrics,
        saveWordLevelLyrics = saveWordLevelLyrics,
        embedLyrics = embedLyrics,
        writeAudioTags = writeAudioTags,
        embedCoverArt = embedCoverArt,
    )
}

/** Immutable UI/store snapshot. Signed source URLs and credentials are deliberately absent. */
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val songId: Long,
    val songName: String,
    val artists: String,
    val album: String,
    val requestedQuality: String,
    val actualQuality: String? = null,
    val filePath: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val errorType: DownloadErrorType? = null,
    val errorSummary: String? = null,
    val automaticRetriesUsed: Int = 0,
    /** HTTP validators are safe to persist; the signed URL itself is not. */
    val etag: String? = null,
    val lastModified: String? = null,
    val collectionImportIds: List<String> = emptyList(),
    val coverUrl: String = "",
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val releaseYear: Int? = null,
    val albumArtist: String = "",
    val aliases: List<String> = emptyList(),
    val translatedTitles: List<String> = emptyList(),
    val postProcessingOptions: AudioPostProcessingOptions = AudioPostProcessingOptions(),
    val postProcessingStatus: PostProcessingStatus = PostProcessingStatus.NOT_REQUESTED,
    val postProcessingMessage: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(songId > 0L)
        require(songName.isNotBlank())
        require(requestedQuality in setOf("standard", "exhigh", "lossless", "hires"))
        require(filePath.isNotBlank())
        require(bytesDownloaded >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
        require(speedBytesPerSecond >= 0L)
        require(automaticRetriesUsed in 0..MAX_AUTOMATIC_DOWNLOAD_RETRIES)
    }

    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesDownloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

internal fun DownloadTask.isPostProcessingRetryable(): Boolean =
    status == DownloadTaskStatus.COMPLETED &&
        postProcessingStatus in setOf(PostProcessingStatus.FAILED, PostProcessingStatus.WARNING)

internal fun DownloadTask.isBulkRetryable(): Boolean =
    status == DownloadTaskStatus.FAILED || isPostProcessingRetryable()

internal fun DownloadTask.belongsInFailedFilter(): Boolean =
    status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED) || isPostProcessingRetryable()

/** The only code allowed to apply download state transitions. */
internal object DownloadTaskStateMachine {
    private val allowed = mapOf(
        DownloadTaskStatus.QUEUED to setOf(
            DownloadTaskStatus.RESOLVING, DownloadTaskStatus.PAUSED,
            DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED,
        ),
        DownloadTaskStatus.RESOLVING to setOf(
            DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.PAUSED,
            DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED,
        ),
        DownloadTaskStatus.DOWNLOADING to setOf(
            DownloadTaskStatus.RESOLVING, DownloadTaskStatus.PAUSED, DownloadTaskStatus.COMPLETED,
            DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED,
        ),
        DownloadTaskStatus.PAUSED to setOf(
            DownloadTaskStatus.QUEUED, DownloadTaskStatus.FAILED,
            DownloadTaskStatus.CANCELLED,
        ),
        DownloadTaskStatus.COMPLETED to setOf(DownloadTaskStatus.FAILED),
        DownloadTaskStatus.FAILED to setOf(
            DownloadTaskStatus.QUEUED, DownloadTaskStatus.CANCELLED,
        ),
        DownloadTaskStatus.CANCELLED to setOf(DownloadTaskStatus.QUEUED),
    )

    fun transition(
        task: DownloadTask,
        to: DownloadTaskStatus,
        nowMillis: Long = System.currentTimeMillis(),
        errorType: DownloadErrorType? = null,
        errorSummary: String? = null,
    ): DownloadTask {
        check(to in allowed.getValue(task.status)) {
            "非法下载任务状态转换：${task.status} -> $to"
        }
        return task.copy(
            status = to,
            updatedAtMillis = nowMillis,
            speedBytesPerSecond = if (to == DownloadTaskStatus.DOWNLOADING) {
                task.speedBytesPerSecond
            } else {
                0L
            },
            errorType = errorType,
            errorSummary = errorSummary?.let(DownloadTaskStore::sanitizeSummary),
        )
    }

    fun progress(
        task: DownloadTask,
        bytesDownloaded: Long,
        totalBytes: Long?,
        speedBytesPerSecond: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): DownloadTask {
        check(task.status == DownloadTaskStatus.DOWNLOADING) {
            "只能更新下载中任务的传输进度"
        }
        return task.copy(
            bytesDownloaded = bytesDownloaded.coerceAtLeast(0L),
            totalBytes = totalBytes?.coerceAtLeast(0L),
            speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
            updatedAtMillis = nowMillis,
        )
    }
}
