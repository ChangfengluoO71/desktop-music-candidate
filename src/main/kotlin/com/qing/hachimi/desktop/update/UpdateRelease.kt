package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.SemVer
import okhttp3.HttpUrl

internal enum class UpdateChannel { PREVIEW, STABLE }

internal data class InstallerAsset(
    val name: String,
    val url: HttpUrl,
    val size: Long,
    val sha256: String,
    val releaseTag: String? = null,
)

internal data class UpdateRelease(
    val version: SemVer,
    val tagName: String,
    val prerelease: Boolean,
    val notes: String,
    val asset: InstallerAsset,
)

internal fun interface ReleaseProvider {
    suspend fun check(etag: String?): ReleaseCheckResult
}

internal enum class ReleaseCheckFailure {
    NETWORK,
    TIMEOUT,
    HTTP_ERROR,
    RATE_LIMITED,
    INVALID_RESPONSE,
    INVALID_METADATA,
    MISSING_INSTALLER_ASSET,
    AMBIGUOUS_INSTALLER_ASSET,
}

internal enum class UpdateFailureCategory {
    NETWORK,
    TIMEOUT,
    HTTP_ERROR,
    RATE_LIMITED,
    CHECK_COOLDOWN,
    INVALID_RESPONSE,
    INVALID_METADATA,
    MISSING_INSTALLER_ASSET,
    AMBIGUOUS_INSTALLER_ASSET,
    REDIRECT_POLICY_REJECTED,
    TLS_FAILED,
    SIZE_MISMATCH,
    DIGEST_MISMATCH,
    INSUFFICIENT_SPACE,
    WRITE_FAILED,
    INSTALLER_LAUNCH_DENIED,
    INSTALLER_FAILED,
    VERSION_MISMATCH,
}

internal enum class UpdateRateLimitKind {
    PRIMARY_API_QUOTA,
    RETRY_AFTER,
    TOO_MANY_REQUESTS,
    UNKNOWN,
}

internal sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val release: UpdateRelease, val etag: String?) : ReleaseCheckResult
    data class UpToDate(val etag: String?) : ReleaseCheckResult
    data class NotModified(val etag: String?) : ReleaseCheckResult
    data class Failed(
        val category: ReleaseCheckFailure,
        val retryAfterEpochMillis: Long? = null,
        /** Kept null by design: remote response bodies and URLs are never exposed as failure text. */
        val message: String? = null,
        val rateLimitKind: UpdateRateLimitKind? = null,
    ) : ReleaseCheckResult
}
