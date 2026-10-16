package com.qing.hachimi.desktop

/** Temporary, isolated identity for the independent candidate build. */
object DesktopProductIdentity {
    const val DISPLAY_NAME = "Desktop Music Candidate"
    const val INSTALLER_NAME = "DesktopMusicCandidate"
    const val DATA_DIRECTORY = "DesktopMusicCandidate"
    const val CREDENTIAL_TARGET = "DesktopMusicCandidate.Session.MUSIC_U"
    const val DEFAULT_DOWNLOAD_FOLDER = "DesktopMusicCandidate"
    const val WINDOW_TITLE = DISPLAY_NAME
    const val VERSION = BuildInfo.VERSION
    const val USER_AGENT = "$INSTALLER_NAME/$VERSION"
}
