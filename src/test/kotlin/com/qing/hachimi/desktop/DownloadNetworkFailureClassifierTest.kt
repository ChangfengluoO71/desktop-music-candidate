package com.qing.hachimi.desktop

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DownloadNetworkFailureClassifierTest {
    @Test
    fun `classifies DNS route connection TLS and CDN HTTP failures separately`() {
        assertEquals("DNS_RESOLUTION_FAILED", NetworkFailureClassifier.category(UnknownHostException("secret host")))
        assertEquals("PROXY_OR_ROUTE_UNAVAILABLE", NetworkFailureClassifier.category(NoRouteToHostException("private route")))
        assertEquals("CONNECTION_FAILED", NetworkFailureClassifier.category(ConnectException("refused")))
        assertEquals("TLS_FAILED", NetworkFailureClassifier.category(SSLHandshakeException("certificate detail")))
        assertEquals("CDN_HTTP_ERROR", NetworkFailureClassifier.category(DownloadHttpException(503)))
    }

    @Test
    fun `categorizes URL policy and file commit errors without exception text`() {
        val policyEvent = DownloadDiagnosticEvent(
            taskId = "test-task",
            songId = 1,
            requestedQuality = "exhigh",
            stage = DownloadDiagnosticStage.URL_VALIDATION,
            elapsedMillis = 1,
            outcome = DownloadDiagnosticOutcome.FAILED,
            error = SourceResolutionFailure.AudioUrlPolicy(AudioUrlFailure.NON_PUBLIC_DNS_TARGET),
        ).toSafeJson()
        val commitEvent = DownloadDiagnosticEvent(
            taskId = "test-task",
            songId = 1,
            requestedQuality = "exhigh",
            stage = DownloadDiagnosticStage.FILE_COMMIT,
            elapsedMillis = 1,
            outcome = DownloadDiagnosticOutcome.FAILED,
            error = SourceResolutionFailure.FileCommitFailure,
        ).toSafeJson()

        assertEquals("URL_POLICY_REJECTED", policyEvent.optString("errorCategory"))
        assertEquals("FILE_COMMIT_FAILED", commitEvent.optString("errorCategory"))
        assertFalse(policyEvent.toString().contains("secret"))
    }
}
