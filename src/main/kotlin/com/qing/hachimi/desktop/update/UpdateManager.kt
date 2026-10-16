package com.qing.hachimi.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val manual: Boolean) : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val bytesReceived: Long, val totalBytes: Long) : UpdateState
    data class Verifying(val release: UpdateRelease) : UpdateState
    data class ReadyToInstall(val release: UpdateRelease, val path: Path) : UpdateState
    data class Failed(val category: UpdateFailureCategory, val retryAfterEpochMillis: Long? = null) : UpdateState
}

/** Isolated background update work; failures never escape into playback or the shell. */
internal class UpdateManager(
    private val releaseProvider: ReleaseProvider,
    private val stager: InstallerPackageStager,
    initialEtag: String? = null,
    initialRelease: UpdateRelease? = null,
    initialRetryAfterEpochMillis: Long? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<UpdateState>(initialRelease?.let(UpdateState::Available) ?: UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    @Volatile private var etag: String? = initialEtag
    @Volatile private var latestRelease: UpdateRelease? = initialRelease
    @Volatile private var retryNotBefore: Long? = initialRetryAfterEpochMillis
    @Volatile private var activeCheck: Job? = null
    @Volatile private var activeDownload: Job? = null

    val cachedEtag: String? get() = etag
    val cachedRelease: UpdateRelease? get() = latestRelease
    val cachedRetryAfterEpochMillis: Long? get() = retryNotBefore

    fun reportFailure(category: UpdateFailureCategory) {
        mutableState.value = UpdateState.Failed(category)
    }

    fun check(manual: Boolean): Job = synchronized(lock) {
        activeCheck?.takeIf(Job::isActive)?.let { return@synchronized it }
        activeDownload?.takeIf(Job::isActive)?.let { return@synchronized it }
        val retryAt = retryNotBefore
        if (retryAt != null && nowEpochMillis() < retryAt) {
            mutableState.value = UpdateState.Failed(UpdateFailureCategory.RATE_LIMITED, retryAt)
            val retryJob = scope.launch(start = CoroutineStart.LAZY) { }
            retryJob.start()
            return@synchronized retryJob
        }

        mutableState.value = UpdateState.Checking(manual)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = releaseProvider.check(etag)) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        etag = result.etag ?: etag
                        latestRelease = result.release
                        retryNotBefore = null
                        mutableState.value = UpdateState.Available(result.release)
                    }
                    is ReleaseCheckResult.UpToDate -> {
                        etag = result.etag ?: etag
                        latestRelease = null
                        retryNotBefore = null
                        mutableState.value = UpdateState.UpToDate
                    }
                    is ReleaseCheckResult.NotModified -> {
                        etag = result.etag ?: etag
                        mutableState.value = latestRelease?.let(UpdateState::Available) ?: UpdateState.UpToDate
                    }
                    is ReleaseCheckResult.Failed -> {
                        val retryAt = if (result.category == ReleaseCheckFailure.RATE_LIMITED) {
                            result.retryAfterEpochMillis?.coerceAtLeast(nowEpochMillis())
                                ?: (nowEpochMillis() + DEFAULT_RATE_LIMIT_BACKOFF_MS)
                        } else null
                        retryNotBefore = retryAt
                        mutableState.value = UpdateState.Failed(result.category.toUpdateFailureCategory(), retryAt)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = latestRelease?.let(UpdateState::Available) ?: UpdateState.Idle
                throw cancelled
            } catch (failure: Throwable) {
                val category = when (failure) {
                    is java.net.SocketTimeoutException -> UpdateFailureCategory.TIMEOUT
                    is IOException -> UpdateFailureCategory.NETWORK
                    else -> UpdateFailureCategory.INVALID_RESPONSE
                }
                mutableState.value = UpdateState.Failed(category)
            }
        }
        activeCheck = job
        job.invokeOnCompletion { synchronized(lock) { if (activeCheck === job) activeCheck = null } }
        job.start()
        job
    }

    fun download(): Job = synchronized(lock) {
        activeDownload?.takeIf(Job::isActive)?.let { return@synchronized it }
        val release = when (val current = mutableState.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> latestRelease
            else -> null
        } ?: latestRelease
        if (release == null) {
            val idleJob = scope.launch(start = CoroutineStart.LAZY) { }
            idleJob.start()
            return@synchronized idleJob
        }

        val jobRef = AtomicReference<Job?>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutableState.value = UpdateState.Downloading(release, 0L, release.asset.size)
            var lastProgressUpdateNanos = 0L
            try {
                val path = stager.stage(
                    release.asset,
                    onProgress = { bytesReceived, totalBytes ->
                        val now = System.nanoTime()
                        val isFinal = totalBytes > 0 && bytesReceived >= totalBytes
                        if (isFinal || now - lastProgressUpdateNanos >= PROGRESS_UPDATE_INTERVAL_NANOS) {
                            lastProgressUpdateNanos = now
                            mutableState.value = UpdateState.Downloading(
                                release,
                                bytesReceived.coerceIn(0L, totalBytes.coerceAtLeast(0L)),
                                totalBytes,
                            )
                        }
                    },
                    onVerifying = { mutableState.value = UpdateState.Verifying(release) },
                )
                mutableState.value = UpdateState.ReadyToInstall(release, path)
            } catch (cancelled: CancellationException) {
                mutableState.value = UpdateState.Available(release)
                throw cancelled
            } catch (failure: InstallerStagingException) {
                mutableState.value = UpdateState.Failed(failure.category)
            } catch (failure: Throwable) {
                val category = when (failure) {
                    is java.net.SocketTimeoutException -> UpdateFailureCategory.TIMEOUT
                    is IOException -> UpdateFailureCategory.NETWORK
                    else -> UpdateFailureCategory.WRITE_FAILED
                }
                mutableState.value = UpdateState.Failed(category)
            }
        }
        jobRef.set(job)
        activeDownload = job
        job.invokeOnCompletion { synchronized(lock) { if (activeDownload === jobRef.get()) activeDownload = null } }
        job.start()
        job
    }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    fun retry(): Job = when (val current = mutableState.value) {
        is UpdateState.Failed -> when {
            current.category == UpdateFailureCategory.RATE_LIMITED -> check(manual = true)
            latestRelease != null -> download()
            else -> check(manual = true)
        }
        else -> download()
    }

    override fun close() {
        scope.cancel()
    }

    private fun ReleaseCheckFailure.toUpdateFailureCategory(): UpdateFailureCategory = when (this) {
        ReleaseCheckFailure.NETWORK -> UpdateFailureCategory.NETWORK
        ReleaseCheckFailure.TIMEOUT -> UpdateFailureCategory.TIMEOUT
        ReleaseCheckFailure.HTTP_ERROR -> UpdateFailureCategory.HTTP_ERROR
        ReleaseCheckFailure.RATE_LIMITED -> UpdateFailureCategory.RATE_LIMITED
        ReleaseCheckFailure.INVALID_RESPONSE -> UpdateFailureCategory.INVALID_RESPONSE
        ReleaseCheckFailure.INVALID_METADATA -> UpdateFailureCategory.INVALID_METADATA
        ReleaseCheckFailure.MISSING_INSTALLER_ASSET -> UpdateFailureCategory.MISSING_INSTALLER_ASSET
        ReleaseCheckFailure.AMBIGUOUS_INSTALLER_ASSET -> UpdateFailureCategory.AMBIGUOUS_INSTALLER_ASSET
    }

    private companion object {
        const val DEFAULT_RATE_LIMIT_BACKOFF_MS = 60 * 60 * 1_000L
        const val PROGRESS_UPDATE_INTERVAL_NANOS = 200_000_000L
    }
}
