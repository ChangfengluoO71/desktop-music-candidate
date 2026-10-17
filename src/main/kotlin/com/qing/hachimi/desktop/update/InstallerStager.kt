package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.DesktopNetworkClientFactory
import com.qing.hachimi.desktop.DesktopNetworkSettings
import com.qing.hachimi.desktop.DesktopProductIdentity
import com.qing.hachimi.desktop.SemVer
import com.qing.hachimi.desktop.desktopAppDataDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal interface InstallerPackageStager {
    suspend fun stage(
        asset: InstallerAsset,
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
    ): Path
}

internal class InstallerStagingException(
    val category: UpdateFailureCategory,
) : IOException(category.name)

/** Streams only the fixed DMC Inno asset, verifies its exact size and SHA-256, then atomically stages it. */
internal class InstallerStager(
    private val stagingDirectory: Path = desktopAppDataDirectory().resolve("updates"),
    private val client: OkHttpClient = defaultClient(),
    private val networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(DesktopNetworkSettings()),
    private val allowedRedirectHosts: Set<String> = GITHUB_ASSET_HOSTS,
    private val downloadMirrorTemplateProvider: () -> String? = { null },
) : InstallerPackageStager {
    override suspend fun stage(
        asset: InstallerAsset,
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        try {
            stageOnIo(asset, onProgress, onVerifying)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InstallerStagingException) {
            throw failure
        } catch (failure: Throwable) {
            when {
                failure.causedBy<SSLHandshakeException>() || failure.causedBy<SSLPeerUnverifiedException>() ->
                    throw InstallerStagingException(UpdateFailureCategory.TLS_FAILED)
                failure.causedBy<SocketTimeoutException>() || failure.message.orEmpty().contains("timeout", ignoreCase = true) ->
                    throw InstallerStagingException(UpdateFailureCategory.TIMEOUT)
                failure is IOException -> throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
                else -> throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
            }
        }
    }

    private suspend fun stageOnIo(
        asset: InstallerAsset,
        onProgress: (Long, Long) -> Unit,
        onVerifying: () -> Unit,
    ): Path {
        val expected = validatedAsset(asset)
        val configuredTemplate = runCatching(downloadMirrorTemplateProvider).getOrElse {
            throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
        }
        val mirrorTemplate = if (configuredTemplate.isNullOrBlank()) null else {
            UpdateMirrorTemplate.parse(configuredTemplate)
                ?: throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
        }
        val initialRequestUrl = mirrorTemplate?.let { mirror ->
            runCatching { mirror.route(expected.initialUrl, UpdateMirrorUse.INSTALLER_DOWNLOAD) }
                .getOrElse { throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA) }
        } ?: expected.initialUrl
        val directory = stagingDirectory.toAbsolutePath().normalize()
        val target = directory.resolve(expected.name).normalize()
        val part = directory.resolve("${expected.name}.part").normalize()
        if (!target.startsWith(directory) || !part.startsWith(directory)) {
            throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
        }
        try {
            Files.createDirectories(directory)
            Files.deleteIfExists(part)
        } catch (_: IOException) {
            throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
        }

        if (Files.isRegularFile(target)) {
            val existingDigest = runCatching { sha256(target) }.getOrNull()
            if (Files.size(target) == expected.size && existingDigest == expected.sha256) {
                onVerifying()
                onProgress(expected.size, expected.size)
                return target
            }
            Files.deleteIfExists(target)
        }

        val usableBytes = runCatching { Files.getFileStore(directory).usableSpace }.getOrDefault(-1L)
        if (usableBytes >= 0 && usableBytes < expected.size + MIN_FREE_SPACE_MARGIN_BYTES) {
            throw InstallerStagingException(UpdateFailureCategory.INSUFFICIENT_SPACE)
        }

        var committed = false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var currentUrl = initialRequestUrl
            var redirects = 0
            var received = 0L
            FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    validateRedirectTarget(currentUrl, expected.sourcePath, initialRequestUrl, mirrorTemplate?.host)
                    val request = Request.Builder()
                        .url(currentUrl)
                        .header("Accept", "application/octet-stream")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", DesktopProductIdentity.USER_AGENT)
                        .get()
                        .build()
                    val routedClient = networkFactory.clientFor(client, currentUrl.toUri())
                    val redirectUrl = withCancelableCall(routedClient.newCall(request)) { currentResponse ->
                            if (currentResponse.code in REDIRECT_CODES) {
                                if (redirects++ >= MAX_REDIRECTS) {
                                    throw InstallerStagingException(UpdateFailureCategory.REDIRECT_POLICY_REJECTED)
                                }
                                val location = currentResponse.header("Location")
                                    ?: throw InstallerStagingException(UpdateFailureCategory.REDIRECT_POLICY_REJECTED)
                                currentUrl.resolve(location)
                                    ?: throw InstallerStagingException(UpdateFailureCategory.REDIRECT_POLICY_REJECTED)
                            } else {
                                if (currentResponse.code != 200) {
                                    throw InstallerStagingException(UpdateFailureCategory.HTTP_ERROR)
                                }
                                val body = currentResponse.body
                                val contentLength = body.contentLength()
                                if (contentLength >= 0 && contentLength != expected.size) {
                                    throw InstallerStagingException(UpdateFailureCategory.SIZE_MISMATCH)
                                }
                                val source = body.source()
                                val buffer = Buffer()
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = networkOperation { source.read(buffer, TRANSFER_BUFFER_BYTES) }
                                    if (read == -1L) break
                                    received += read
                                    if (received > expected.size) {
                                        throw InstallerStagingException(UpdateFailureCategory.SIZE_MISMATCH)
                                    }
                                    val bytes = buffer.readByteArray(read)
                                    digest.update(bytes)
                                    val byteBuffer = ByteBuffer.wrap(bytes)
                                    try {
                                        while (byteBuffer.hasRemaining()) output.write(byteBuffer)
                                    } catch (_: IOException) {
                                        throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
                                    }
                                    runCatching { onProgress(received, expected.size) }
                                }
                                try {
                                    output.force(true)
                                } catch (_: IOException) {
                                    throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
                                }
                                null
                            }
                    }
                    if (redirectUrl != null) {
                        validateRedirectTarget(redirectUrl, expected.sourcePath, initialRequestUrl, mirrorTemplate?.host)
                        currentUrl = redirectUrl
                        continue
                    }
                    break
                }
            }

            if (received != expected.size) throw InstallerStagingException(UpdateFailureCategory.SIZE_MISMATCH)
            runCatching(onVerifying)
            val actualDigest = digest.digest().toHex()
            if (!actualDigest.equals(expected.sha256, ignoreCase = true)) {
                throw InstallerStagingException(UpdateFailureCategory.DIGEST_MISMATCH)
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                throw InstallerStagingException(UpdateFailureCategory.WRITE_FAILED)
            }
            committed = true
            target
        } finally {
            if (!committed) runCatching { Files.deleteIfExists(part) }
        }
    }

    private fun validatedAsset(asset: InstallerAsset): ValidatedAsset {
        val prefix = "${DesktopProductIdentity.INSTALLER_NAME}-"
        val versionText = asset.name.removePrefix(prefix).removeSuffix("-Setup.exe")
        val version = SemVer.parse(versionText)
        if (asset.name != "$prefix$versionText-Setup.exe" || version == null || asset.size !in 1..MAX_PACKAGE_BYTES ||
            !SHA256_PATTERN.matches(asset.sha256)
        ) {
            throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
        }
            val releaseTag = asset.releaseTag ?: "v$versionText"
            if (releaseTag !in setOf(versionText, "v$versionText")) {
                throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
            }
            val sourcePath = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/$releaseTag/${asset.name}"
        val url = asset.url
        if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.querySize != 0 || url.fragment != null ||
            url.encodedPath != sourcePath
        ) {
            throw InstallerStagingException(UpdateFailureCategory.INVALID_METADATA)
        }
        return ValidatedAsset(asset.name, asset.size, asset.sha256.lowercase(Locale.ROOT), sourcePath, url)
    }

    private fun validateRedirectTarget(
        url: HttpUrl,
        sourcePath: String,
        initialRequestUrl: HttpUrl,
        mirrorHost: String?,
    ) {
        val host = url.host.lowercase(Locale.ROOT)
        val isMirrorHost = mirrorHost?.equals(host, ignoreCase = true) == true
        val allowed = isMirrorHost || allowedRedirectHosts.any { it.equals(host, ignoreCase = true) }
        val expectedPort = if (isMirrorHost) initialRequestUrl.port else 443
        if (url.scheme != "https" || url.port != expectedPort || !allowed ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            (isMirrorHost && url.encodedPath != initialRequestUrl.encodedPath) ||
            (host == "github.com" && (url.encodedPath != sourcePath || url.querySize != 0))
        ) {
            throw InstallerStagingException(UpdateFailureCategory.REDIRECT_POLICY_REJECTED)
        }
        if (host == "github.com" && mirrorHost != null) {
            throw InstallerStagingException(UpdateFailureCategory.REDIRECT_POLICY_REJECTED)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(TRANSFER_BUFFER_BYTES.toInt())
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private data class ValidatedAsset(
        val name: String,
        val size: Long,
        val sha256: String,
        val sourcePath: String,
        val initialUrl: HttpUrl,
    )

    private inline fun <reified T : Throwable> Throwable.causedBy(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }

    private suspend fun <T> networkOperation(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        currentCoroutineContext().ensureActive()
        throw networkFailure(failure)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> withCancelableCall(call: Call, block: suspend (Response) -> T): T {
        val job = currentCoroutineContext()[Job]
        val cancellationHandle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            val response = networkOperation { call.execute() }
            return try {
                response.use { block(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: InstallerStagingException) {
                throw failure
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw networkFailure(failure)
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun networkFailure(failure: IOException): InstallerStagingException = when {
        failure.causedBy<SSLHandshakeException>() || failure.causedBy<SSLPeerUnverifiedException>() ->
            InstallerStagingException(UpdateFailureCategory.TLS_FAILED)
        failure.causedBy<SocketTimeoutException>() || failure.message.orEmpty().contains("timeout", ignoreCase = true) ->
            InstallerStagingException(UpdateFailureCategory.TIMEOUT)
        else -> InstallerStagingException(UpdateFailureCategory.NETWORK)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val REPOSITORY_OWNER = "ChangfengluoO71"
        const val REPOSITORY_NAME = "desktop-music-candidate"
        const val MAX_PACKAGE_BYTES = 1_500_000_000L
        const val MIN_FREE_SPACE_MARGIN_BYTES = 16L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val TRANSFER_BUFFER_BYTES = 64L * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        val GITHUB_ASSET_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
        )

        fun defaultClient() = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
