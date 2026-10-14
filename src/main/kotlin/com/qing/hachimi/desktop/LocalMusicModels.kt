package com.qing.hachimi.desktop

import java.util.Locale

enum class LocalAudioFormat {
    MP3,
    FLAC,
    WAV,
    M4A,
    AAC,
    OTHER,
}

enum class LocalMetadataStatus {
    READABLE,
    PARTIAL,
    MISSING,
    UNSUPPORTED,
    DAMAGED,
    ACCESS_DENIED,
    MISSING_FILE,
}

enum class LocalTitleSource { TAG, FILENAME, UNKNOWN }

enum class LocalMusicBrowseMode { ALBUMS, ARTISTS, SONGS, FOLDERS }

enum class LocalMusicSortMode { TITLE, ARTIST, ALBUM, FORMAT, DURATION }

internal fun normalizedLibraryPath(path: String): String =
    path.replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/').lowercase(Locale.ROOT)

enum class LocalScanErrorKind {
    INVALID_ROOT,
    ROOT_MISSING,
    ACCESS_DENIED,
    FILE_MISSING,
    UNSUPPORTED_FORMAT,
    DAMAGED_AUDIO,
    TAG_READ_FAILED,
    FILE_CHANGED,
    HASH_FAILED,
}

data class LocalScanError(
    val kind: LocalScanErrorKind,
    val summary: String,
)

data class LocalMusicRoot(
    val path: String,
    val addedAtMillis: Long,
    val autoWatch: Boolean = false,
)

data class LocalTrack(
    /** Stable for this path instance; a moved file receives a new id. */
    val id: String,
    /** Case-normalized path key used for identity on Windows. */
    val pathKey: String,
    val filePath: String,
    val fileSize: Long,
    val lastModifiedMillis: Long,
    val format: LocalAudioFormat,
    val codec: String?,
    val lossiness: AudioLossiness,
    val durationSeconds: Double?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val title: String,
    val titleSource: LocalTitleSource,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val metadataStatus: LocalMetadataStatus,
    val error: LocalScanError? = null,
    val contentSha256: String? = null,
    val scannedAtMillis: Long = System.currentTimeMillis(),
) {
    val audioQuality: LocalAudioQuality
        get() = LocalAudioQuality(
            codec = codec,
            lossiness = lossiness,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            durationSeconds = durationSeconds,
            fileSizeBytes = fileSize,
        )
}

enum class AudioLossiness { LOSSY, LOSSLESS, UNKNOWN }

data class LocalAudioQuality(
    val codec: String?,
    val lossiness: AudioLossiness,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val durationSeconds: Double?,
    val fileSizeBytes: Long,
) {
    fun summary(): String = buildList {
        add(codec?.takeIf(String::isNotBlank) ?: "编码未知")
        add(when (lossiness) {
            AudioLossiness.LOSSY -> "有损编码"
            AudioLossiness.LOSSLESS -> "无损编码"
            AudioLossiness.UNKNOWN -> "编码属性未知"
        })
        bitrateKbps?.takeIf { it > 0 }?.let { add("${it}kbps") }
        sampleRateHz?.takeIf { it > 0 }?.let { add("${it / 1000.0}kHz") }
        bitDepth?.takeIf { it > 0 }?.let { add("${it}bit") }
        durationSeconds?.takeIf { it > 0 }?.let { add("${"%.1f".format(Locale.ROOT, it)}秒") }
        add("${fileSizeBytes}B")
    }.joinToString(" · ")
}

enum class QualityParameterComparison {
    HIGHER_PARAMETERS,
    LOWER_PARAMETERS,
    SAME_PARAMETERS,
    INCOMPARABLE_CODECS,
    UNKNOWN,
}

enum class IdentityStatus { UNKNOWN, NEEDS_REVIEW, POSSIBLE_MATCH, CONFLICT }

enum class UpgradePreviewStatus { NEEDS_REVIEW, POSSIBLE_UPGRADE, NOT_HIGHER, NO_CANDIDATE }

data class LocalUpgradeCandidate(
    val localTrackId: String,
    val candidateSongId: Long?,
    val localQuality: LocalAudioQuality,
    val candidateQuality: LocalAudioQuality?,
    val identityEvidence: List<String>,
    val identityStatus: IdentityStatus,
    val qualityComparison: QualityParameterComparison,
    val upgradeStatus: UpgradePreviewStatus,
    val reason: String,
)

data class LocalScanProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val currentPath: String?,
    val rootPath: String?,
)

data class LocalMusicScanResult(
    val tracks: List<LocalTrack>,
    val scannedFiles: Int,
    val reusedMetadata: Int,
    val failedFiles: Int,
    val unsupportedFiles: Int,
    val rootErrors: List<LocalScanError>,
)

data class ExactDuplicateGroup(
    val sha256: String,
    val tracks: List<LocalTrack>,
)

data class SimilarDuplicateGroup(
    val tracks: List<LocalTrack>,
    val matchingEvidence: List<String>,
    val conflicts: List<String>,
)

data class DuplicateAnalysisResult(
    val exactDuplicates: List<ExactDuplicateGroup>,
    val similarCandidates: List<SimilarDuplicateGroup>,
    val hashedFiles: Int,
    val failedHashes: Int,
)

fun compareAudioParameters(
    local: LocalAudioQuality,
    candidate: LocalAudioQuality,
): QualityParameterComparison {
    if (local.codec.isNullOrBlank() || candidate.codec.isNullOrBlank()) return QualityParameterComparison.UNKNOWN
    if (!local.codec.equals(candidate.codec, ignoreCase = true)) {
        return QualityParameterComparison.INCOMPARABLE_CODECS
    }
    val localValues = comparableQualityParameters(local)
    val candidateValues = comparableQualityParameters(candidate)
    if (localValues == null || candidateValues == null) return QualityParameterComparison.UNKNOWN
    val relation = compareValuesBy(
        candidateValues,
        localValues,
        { it.first },
        { it.second },
        { it.third },
    )
    return when {
        relation > 0 -> QualityParameterComparison.HIGHER_PARAMETERS
        relation < 0 -> QualityParameterComparison.LOWER_PARAMETERS
        else -> QualityParameterComparison.SAME_PARAMETERS
    }
}

private fun comparableQualityParameters(quality: LocalAudioQuality): Triple<Int, Int, Int>? {
    val rate = quality.sampleRateHz?.takeIf { it > 0 } ?: return null
    return when (quality.lossiness) {
        AudioLossiness.LOSSY -> {
            val bitrate = quality.bitrateKbps?.takeIf { it > 0 } ?: return null
            Triple(bitrate, rate, 0)
        }
        AudioLossiness.LOSSLESS -> {
            val depth = quality.bitDepth?.takeIf { it > 0 } ?: return null
            Triple(rate, depth, 0)
        }
        AudioLossiness.UNKNOWN -> {
            val bitrate = quality.bitrateKbps?.takeIf { it > 0 } ?: return null
            Triple(bitrate, rate, quality.bitDepth ?: 0)
        }
    }
}

object LocalMusicAnalyzer {
    private val titleVariantPatterns = listOf(
        "(?i)\\blive\\b|现场(版)?|演唱会(版)?" to "现场版",
        "(?i)\\b(remix|mix)\\b|混音(版)?" to "混音版",
        "(?i)\\b(remaster(ed)?)\\b|重制(版)?|再版" to "重制版",
        "(?i)\\b(cover|karaoke)\\b|翻唱(版)?|卡拉OK" to "翻唱/伴奏版",
        "(?i)\\b(instrumental)\\b|伴奏(版)?|纯音乐" to "伴奏版",
        "(?i)\\b(acoustic)\\b|不插电(版)?" to "不插电版",
    )

    fun analyzeSimilarDuplicates(tracks: List<LocalTrack>): List<SimilarDuplicateGroup> {
        val eligible = tracks.filter {
            it.title.isNotBlank() && it.artists.any(String::isNotBlank) &&
                it.metadataStatus !in setOf(LocalMetadataStatus.DAMAGED, LocalMetadataStatus.UNSUPPORTED)
        }
        return eligible.groupBy { track ->
            normalizeTitleForGrouping(track.title) to normalizeIdentity(track.artists.firstOrNull().orEmpty())
        }.values.filter { it.size > 1 }.map { group ->
            val conflicts = linkedSetOf<String>()
            val versions = group.map { titleVariant(it.title) }.distinct()
            if (versions.size > 1) conflicts += "标题包含不同版本标记（现场、重制、翻唱或伴奏等）"
            if (hasDurationConflict(group)) conflicts += "时长差异明显，可能是不同剪辑或录音版本"
            if (group.map { normalizeIdentity(it.album) }.filter(String::isNotBlank).distinct().size > 1) {
                conflicts += "专辑信息不同"
            }
            val evidence = buildList {
                add("标准化标题一致")
                add("首位艺术家一致")
                if (group.mapNotNull { it.durationSeconds }.distinct().size <= 1) add("时长接近或未知")
                if (group.any { it.titleSource == LocalTitleSource.FILENAME }) add("部分标题来自文件名，身份置信度较低")
            }
            SimilarDuplicateGroup(group, evidence, conflicts.toList())
        }
    }

    fun previewWithoutOnlineCandidate(track: LocalTrack): LocalUpgradeCandidate = LocalUpgradeCandidate(
        localTrackId = track.id,
        candidateSongId = null,
        localQuality = track.audioQuality,
        candidateQuality = null,
        identityEvidence = buildList {
            if (track.title.isNotBlank()) add("本地标题：${track.title}")
            if (track.artists.isNotEmpty()) add("本地艺术家：${track.artists.joinToString()}")
        },
        identityStatus = IdentityStatus.UNKNOWN,
        qualityComparison = QualityParameterComparison.UNKNOWN,
        upgradeStatus = UpgradePreviewStatus.NO_CANDIDATE,
        reason = "当前没有在线候选；此页仅展示本地音质参数，不会下载或替换文件。",
    )

    fun compareCandidate(track: LocalTrack, candidate: UpgradeCandidateRecord): LocalUpgradeCandidate {
        val evidence = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val localTitle = normalizeIdentity(track.title)
        val candidateTitle = normalizeIdentity(candidate.title)
        if (localTitle.isNotBlank() && candidateTitle.isNotBlank() && localTitle == candidateTitle) {
            evidence += "标准化标题一致"
        } else if (localTitle.isNotBlank() && candidateTitle.isNotBlank()) {
            conflicts += "标题不同"
        }
        val localArtist = normalizeIdentity(track.artists.firstOrNull().orEmpty())
        val candidateArtist = normalizeIdentity(candidate.artists.firstOrNull().orEmpty())
        if (localArtist.isNotBlank() && candidateArtist.isNotBlank() && localArtist == candidateArtist) {
            evidence += "首位艺术家一致"
        } else if (localArtist.isNotBlank() && candidateArtist.isNotBlank()) {
            conflicts += "艺术家不同"
        }
        if (titleVariant(track.title) != titleVariant(candidate.title)) conflicts += "标题版本特征冲突"
        val localDuration = track.durationSeconds
        val candidateDuration = candidate.durationSeconds
        if (localDuration != null && candidateDuration != null && durationDiffers(localDuration, candidateDuration)) {
            conflicts += "时长差异明显"
        } else if (localDuration != null && candidateDuration != null) {
            evidence += "时长接近"
        }
        val qualityComparison = compareAudioParameters(track.audioQuality, candidate.quality)
        val identity = when {
            conflicts.isNotEmpty() -> IdentityStatus.CONFLICT
            evidence.size >= 2 -> IdentityStatus.POSSIBLE_MATCH
            else -> IdentityStatus.NEEDS_REVIEW
        }
        val previewStatus = when {
            identity != IdentityStatus.POSSIBLE_MATCH -> UpgradePreviewStatus.NEEDS_REVIEW
            qualityComparison == QualityParameterComparison.HIGHER_PARAMETERS -> UpgradePreviewStatus.POSSIBLE_UPGRADE
            qualityComparison == QualityParameterComparison.LOWER_PARAMETERS ||
                qualityComparison == QualityParameterComparison.SAME_PARAMETERS -> UpgradePreviewStatus.NOT_HIGHER
            else -> UpgradePreviewStatus.NEEDS_REVIEW
        }
        val reason = when {
            conflicts.isNotEmpty() -> "候选存在身份冲突：${conflicts.joinToString("、")}。需要人工核对。"
            identity != IdentityStatus.POSSIBLE_MATCH -> "身份信息不足，需要人工核对；参数比较不证明音源原始质量。"
            qualityComparison == QualityParameterComparison.HIGHER_PARAMETERS ->
                "同编码参数更高，仅表示技术参数差异，不代表音源更好；需要人工核对后再决定。"
            else -> "没有确认更高的同编码参数；此结果仅供预览。"
        }
        return LocalUpgradeCandidate(
            localTrackId = track.id,
            candidateSongId = candidate.songId,
            localQuality = track.audioQuality,
            candidateQuality = candidate.quality,
            identityEvidence = evidence + conflicts,
            identityStatus = identity,
            qualityComparison = qualityComparison,
            upgradeStatus = previewStatus,
            reason = reason,
        )
    }

    private fun normalizeIdentity(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\s]+"), "")
        .trim()

    private fun normalizeTitleForGrouping(value: String): String {
        val baseTitle = titleVariantPatterns.fold(value) { current, (pattern, _) ->
            Regex(pattern).replace(current, " ")
        }
        return normalizeIdentity(baseTitle)
    }

    private fun titleVariant(title: String): Set<String> = titleVariantPatterns
        .filter { (pattern, _) -> Regex(pattern).containsMatchIn(title) }
        .mapTo(linkedSetOf()) { it.second }

    private fun hasDurationConflict(tracks: List<LocalTrack>): Boolean {
        val lengths = tracks.mapNotNull { it.durationSeconds }
        return lengths.size > 1 && (lengths.maxOrNull()!! - lengths.minOrNull()!!) > 5.0
    }

    private fun durationDiffers(first: Double, second: Double): Boolean =
        kotlin.math.abs(first - second) > maxOf(5.0, maxOf(first, second) * 0.05)
}

data class UpgradeCandidateRecord(
    val songId: Long,
    val title: String,
    val artists: List<String>,
    val durationSeconds: Double?,
    val quality: LocalAudioQuality,
)

fun interface LocalUpgradeCandidateSource {
    suspend fun findCandidates(track: LocalTrack): List<UpgradeCandidateRecord>
}
