package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DesktopQrLoginApiTest {
    @Test
    fun `ticket uses client QR flow and builds the phone login URL`() = runBlocking {
        val transport = QueueQrLoginTransport(
            QrLoginHttpResponse(
                statusCode = 200,
                body = """{"code":200,"unikey":"ticket-test-123","cookie":"NMTID=temporary-cookie; MUSIC_R_T=temporary-session-cookie; Path=/; HttpOnly"}""",
            ),
        )
        val api = DesktopQrLoginApi(transport)

        val ticket = api.createTicket()

        assertEquals("https://music.163.com/login?codekey=ticket-test-123", api.loginUrl(ticket))
        assertEquals("login/qrcode/unikey", transport.requests.single().path)
        assertEquals("""{"type":3}""", transport.requests.single().payload)
        assertEquals(emptyMap(), transport.requests.single().cookies)
        assertEquals(
            mapOf("NMTID" to "temporary-cookie", "MUSIC_R_T" to "temporary-session-cookie"),
            ticket.sessionCookies,
        )
        assertFalse(ticket.toString().contains(ticket.key))
    }

    @Test
    fun `polling distinguishes waiting scanning and phone confirmation`() = runBlocking {
        val transport = QueueQrLoginTransport(
            response(801), response(802),
        )
        val api = DesktopQrLoginApi(transport)
        val ticket = QrLoginTicket("test-key", emptyMap())

        assertEquals(QrLoginPoll.WAITING, api.poll(ticket))
        assertEquals(QrLoginPoll.SCANNED, api.poll(ticket))
        assertEquals("login/qrcode/client/login", transport.requests.first().path)
        val payload = org.json.JSONObject(transport.requests.first().payload)
        assertEquals("test-key", payload.getString("key"))
        assertEquals(3, payload.getInt("type"))
    }

    @Test
    fun `only a successful response with MUSIC_U completes login`() = runBlocking {
        val withoutCredential = QueueQrLoginTransport(response(803))
        val failed = DesktopQrLoginApi(withoutCredential).poll(QrLoginTicket("key", emptyMap()))
        assertEquals(QrLoginPoll.FAILED, failed)

        val withCredential = QueueQrLoginTransport(
            QrLoginHttpResponse(
                statusCode = 200,
                body = """{"code":803}""",
                headers = listOf("MUSIC_U=synthetic-session; Path=/; HttpOnly", "unrelated=ignored; Path=/"),
            ),
        )
        val success = DesktopQrLoginApi(withCredential).poll(QrLoginTicket("key", emptyMap()))

        val authenticated = assertIs<QrLoginPoll.Authenticated>(success)
        assertEquals("synthetic-session", authenticated.musicU)
        assertFalse(authenticated.musicU.contains("unrelated"))
        assertFalse(authenticated.toString().contains(authenticated.musicU))
    }

    @Test
    fun `expired ticket and malformed server responses never expose response data`() = runBlocking {
        val marker = "sensitive-response-${System.nanoTime()}"
        val expired = DesktopQrLoginApi(QueueQrLoginTransport(response(800)))
            .poll(QrLoginTicket("key", emptyMap()))
        val malformed = DesktopQrLoginApi(
            QueueQrLoginTransport(QrLoginHttpResponse(200, "not-json-$marker")),
        ).poll(QrLoginTicket("key", emptyMap()))

        assertEquals(QrLoginPoll.EXPIRED, expired)
        assertEquals(QrLoginPoll.FAILED, malformed)
        assertFalse(malformed.toString().contains(marker))
    }

    @Test
    fun `generated QR code decodes to the intended phone login URL`() {
        val loginUrl = "https://music.163.com/login?codekey=synthetic-ticket"
        val code = DesktopQrCodeEncoder.encode(loginUrl)
        val pixels = IntArray(code.size * code.size) { index ->
            if (code.darkModules[index]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = com.google.zxing.RGBLuminanceSource(code.size, code.size, pixels)
        val decoded = com.google.zxing.MultiFormatReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
        )

        assertEquals(loginUrl, decoded.text)
    }

    private fun response(code: Int) = QrLoginHttpResponse(200, """{"code":$code}""")

    private class QueueQrLoginTransport(vararg responses: QrLoginHttpResponse) : QrLoginTransport {
        val requests = mutableListOf<QrLoginHttpRequest>()
        private val remaining = ArrayDeque(responses.toList())

        override suspend fun post(path: String, payload: String, cookies: Map<String, String>): QrLoginHttpResponse {
            requests += QrLoginHttpRequest(path, payload, cookies)
            return remaining.removeFirst()
        }
    }
}
