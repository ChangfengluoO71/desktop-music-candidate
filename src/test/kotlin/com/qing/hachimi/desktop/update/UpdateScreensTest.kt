package com.qing.hachimi.desktop.update

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.qing.hachimi.desktop.SemVer
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateScreensTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `update details surface paints an opaque theme background`() = runComposeUiTest {
        var expectedBackground = Color.Unspecified
        setContent {
            MiuixTheme(controller = androidx.compose.runtime.remember { ThemeController(ColorSchemeMode.System) }) {
                expectedBackground = MiuixTheme.colorScheme.background
                UpdateDetailsContent(
                    state = UpdateState.Failed(UpdateFailureCategory.RATE_LIMITED),
                    onDownload = {},
                    onCancel = {},
                    onRetry = {},
                    onInstall = {},
                    onLater = {},
                    onCheck = {},
                )
            }
        }

        val surface = onNodeWithTag("update-details-surface").captureToImage()
        assertEquals(expectedBackground, surface.toPixelMap()[surface.width / 2, 4])
    }

    @Test
    fun `rate limit description shows the server supplied retry time`() {
        val retryAt = java.time.Instant.parse("2026-10-17T12:34:00Z").toEpochMilli()
        val description = updateStatusDescription(UpdateState.Failed(UpdateFailureCategory.RATE_LIMITED, retryAt))

        val localRetryTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(retryAt))
        assertTrue(description.contains(localRetryTime))
        assertTrue(description.contains("GitHub"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `retry remains disabled until the stored cooldown ends`() = runComposeUiTest {
        val retryAt = System.currentTimeMillis() + 60_000L
        setContent {
            MiuixTheme(controller = androidx.compose.runtime.remember { ThemeController(ColorSchemeMode.System) }) {
                UpdateDetailsContent(
                    state = UpdateState.Failed(UpdateFailureCategory.RATE_LIMITED, retryAt),
                    onDownload = {},
                    onCancel = {},
                    onRetry = {},
                    onInstall = {},
                    onLater = {},
                    onCheck = {},
                )
            }
        }

        onNodeWithTag("update-retry").assertIsNotEnabled()
        onNodeWithText("重试（", substring = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `download status stays compact and opens details without changing host content`() = runComposeUiTest {
        val release = release()
        var detailsOpened = false
        setContent {
            androidx.compose.foundation.layout.Column {
                MiuixTheme(controller = androidx.compose.runtime.remember { ThemeController(ColorSchemeMode.System) }) {
                    androidx.compose.foundation.layout.Column {
                        Text("Current page and player")
                        UpdateStatusIndicator(
                            UpdateState.Downloading(release, 42, 100),
                            onOpen = { detailsOpened = true },
                        )
                    }
                }
            }
        }

        onNodeWithTag("update-status").assertIsDisplayed().performClick()
        onNodeWithText("Current page and player").assertIsDisplayed()
        assertTrue(detailsOpened)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `details expose cancel for download and retry for failure`() = runComposeUiTest {
        val release = release()
        var currentState by mutableStateOf<UpdateState>(UpdateState.Downloading(release, 5, 10))
        var cancelled = false
        var retried = false
        setContent {
            MiuixTheme(controller = androidx.compose.runtime.remember { ThemeController(ColorSchemeMode.System) }) {
                UpdateDetailsContent(
                    state = currentState,
                    onDownload = {},
                    onCancel = { cancelled = true },
                    onRetry = { retried = true },
                    onInstall = {},
                    onLater = {},
                    onCheck = {},
                )
            }
        }

        onNodeWithTag("update-cancel").assertIsDisplayed().performClick()
        assertTrue(cancelled)
        currentState = UpdateState.Failed(UpdateFailureCategory.DIGEST_MISMATCH)
        // Recompose the same content from the latest state.
        runOnIdle { }
        onNodeWithTag("update-retry").assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    private fun release() = UpdateRelease(
        version = requireNotNull(SemVer.parse("1.3.0")),
        tagName = "v1.3.0",
        prerelease = true,
        notes = "Preview notes",
        asset = InstallerAsset(
            "DesktopMusicCandidate-1.3.0-Setup.exe",
            "https://github.com/ChangfengluO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
            100,
            "a".repeat(64),
        ),
    )
}
