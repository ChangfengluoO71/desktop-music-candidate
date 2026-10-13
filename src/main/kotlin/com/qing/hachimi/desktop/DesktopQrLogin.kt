package com.qing.hachimi.desktop

import com.qing.hachimi.data.api.NeteaseCrypto
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class QrLoginHttpRequest(
    val path: String,
    val payload: String,
    val cookies: Map<String, String>,
)

internal data class QrLoginHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: List<String> = emptyList(),
)

internal fun interface QrLoginTransport {
    suspend fun post(path: String, payload: String, cookies: Map<String, String>): QrLoginHttpResponse
}

class QrLoginTicket internal constructor(
    internal val key: String,
    internal val sessionCookies: Map<String, String>,
) {
    override fun toString(): String = "QrLoginTicket([redacted])"
}

sealed interface QrLoginPoll {
    data object WAITING : QrLoginPoll
    data object SCANNED : QrLoginPoll
    data object EXPIRED : QrLoginPoll
    data object FAILED : QrLoginPoll
    class Authenticated internal constructor(internal val musicU: String) : QrLoginPoll {
        override fun toString(): String = "Authenticated([redacted])"
    }
}

interface DesktopQrLoginGateway {
    suspend fun createTicket(): QrLoginTicket
    suspend fun poll(ticket: QrLoginTicket): QrLoginPoll
    fun loginUrl(ticket: QrLoginTicket): String
}

/** Implements the upstream type=3 mobile QR flow while keeping its bearer ticket in memory only. */
internal class DesktopQrLoginApi(
    private val transport: QrLoginTransport,
) : DesktopQrLoginGateway {
    override suspend fun createTicket(): QrLoginTicket {
        val response = transport.post(
            path = "login/qrcode/unikey",
            payload = """{"type":3}""",
            cookies = emptyMap(),
        )
        if (response.statusCode !in 200..299) throw QrLoginException()
        val json = runCatching { org.json.JSONObject(response.body) }.getOrElse { throw QrLoginException() }
        if (json.optLong("code") != 200L) throw QrLoginException()
        val key = json.optString("unikey").takeIf { it.isNotBlank() } ?: throw QrLoginException()
        val cookies = parseInitialCookies(json.optString("cookie"))
        return QrLoginTicket(key, cookies)
    }

    override suspend fun poll(ticket: QrLoginTicket): QrLoginPoll {
        val response = transport.post(
            path = "login/qrcode/client/login",
            payload = org.json.JSONObject().put("key", ticket.key).put("type", 3).toString(),
            cookies = ticket.sessionCookies,
        )
        if (response.statusCode !in 200..299) return QrLoginPoll.FAILED
        val code = runCatching { org.json.JSONObject(response.body).optLong("code", -1L) }
            .getOrDefault(-1L)
        return when (code) {
            801L -> QrLoginPoll.WAITING
            802L -> QrLoginPoll.SCANNED
            803L -> response.headers.asSequence()
                .mapNotNull(::musicUFromSetCookie)
                .firstOrNull()
                ?.let(QrLoginPoll::Authenticated)
                ?: QrLoginPoll.FAILED
            800L -> QrLoginPoll.EXPIRED
            else -> QrLoginPoll.FAILED
        }
    }

    override fun loginUrl(ticket: QrLoginTicket): String =
        "https://music.163.com/login?codekey=${URLEncoder.encode(ticket.key, Charsets.UTF_8)}"

    private fun parseInitialCookies(value: String): Map<String, String> {
        val attributes = setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly", "priority")
        return value.split(';').mapNotNull { segment ->
            val separator = segment.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = segment.substring(0, separator).trim()
            val content = segment.substring(separator + 1).trim()
            val validName = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }
            if (validName && name.lowercase() !in attributes && content.isNotEmpty()) name to content else null
        }.toMap()
    }

    private fun musicUFromSetCookie(value: String): String? {
        val firstPair = value.substringBefore(';')
        val separator = firstPair.indexOf('=')
        if (separator <= 0 || firstPair.substring(0, separator).trim() != "MUSIC_U") return null
        return firstPair.substring(separator + 1).trim().takeIf(String::isNotBlank)
    }
}

internal class QrLoginException : IOException("QR login request failed")

data class QrCodeData(val size: Int, val darkModules: List<Boolean>)

internal object DesktopQrCodeEncoder {
    fun encode(content: String): QrCodeData = try {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            260,
            260,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        QrCodeData(
            size = matrix.width,
            darkModules = List(matrix.width * matrix.height) { index ->
                matrix[index % matrix.width, index / matrix.width]
            },
        )
    } catch (_: Exception) {
        throw QrLoginException()
    }
}

/** OkHttp adapter with coroutine cancellation wired to Call.cancel(). */
internal class OkHttpQrLoginTransport(
    client: OkHttpClient? = null,
    networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(),
) : QrLoginTransport {
    private val client = client ?: networkFactory.configure(OkHttpClient.Builder())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun post(path: String, payload: String, cookies: Map<String, String>): QrLoginHttpResponse {
        val encrypted = NeteaseCrypto.encryptParams("/eapi/$path", payload)
        val extendedCookies = linkedMapOf(
            "os" to "pc",
            "osver" to "Microsoft-Windows-10-Professional-build-19045-64bit",
            "appver" to "3.1.17.204416",
            "channel" to "netease",
            "deviceId" to (cookies["deviceId"] ?: "pyncm!"),
            "buildver" to System.currentTimeMillis().toString().take(10),
            "resolution" to "1920x1080",
            "versioncode" to "140",
            "mobilename" to "",
        ).apply { putAll(cookies) }
        val cookieHeader = extendedCookies.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("; ") { "${encode(it.key)}=${encode(it.value)}" }
        val form = "params=${encode(encrypted)}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://music.163.com/eapi/$path")
            .header("User-Agent", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)")
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieHeader)
            .post(form)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            QrLoginHttpResponse(
                                statusCode = it.code,
                                body = it.body.string(),
                                headers = it.headers.values("Set-Cookie"),
                            )
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
