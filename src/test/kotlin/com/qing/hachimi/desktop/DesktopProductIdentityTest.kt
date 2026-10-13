package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Path

class DesktopProductIdentityTest {
    @Test
    fun `candidate identity is neutral and isolated from legacy data`() {
        assertEquals("Desktop Music Candidate", DesktopProductIdentity.DISPLAY_NAME)
        assertEquals("DesktopMusicCandidate", DesktopProductIdentity.INSTALLER_NAME)
        assertEquals("DesktopMusicCandidate", DesktopProductIdentity.DATA_DIRECTORY)
        assertEquals(
            "DesktopMusicCandidate.Session.MUSIC_U",
            DesktopProductIdentity.CREDENTIAL_TARGET,
        )
        assertEquals("DesktopMusicCandidate", DesktopProductIdentity.DEFAULT_DOWNLOAD_FOLDER)
        assertEquals("Desktop Music Candidate", DesktopProductIdentity.WINDOW_TITLE)
        assertEquals(DesktopProductIdentity.DATA_DIRECTORY, desktopAppDataDirectory().fileName.toString())
        assertEquals(
            DesktopProductIdentity.DEFAULT_DOWNLOAD_FOLDER,
            Path.of(DesktopSettings().defaultDownloadDirectory).fileName.toString(),
        )
    }
}
