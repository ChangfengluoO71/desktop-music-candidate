# DMC product identity

Decision recorded: 2026-10-13. **DMC** is the selected product name. This file distinguishes the chosen brand from runtime identifiers that still need a coordinated migration.

| Identity surface | Current value | DMC status / migration rule |
|---|---|---|
| User-visible product name | DMC in product documents; the installed preview still displays `Desktop Music Candidate` | Update runtime branding in a dedicated release after checking installer upgrades, shortcuts, support text and existing user data. |
| GitHub repository | `ChangfengluoO71/desktop-music-candidate` (public) | Keep the existing slug for now; renaming the GitHub repository is a separate decision and is not required to use DMC as the product name. |
| Gradle root name | `DesktopMusicCandidate` | Rename only with the runtime branding migration. |
| Gradle group | `org.example.desktopmusic` | Replace with a product-owner-controlled reverse-domain namespace before a stable release. |
| JVM packages | `com.qing.hachimi.desktop`, `com.qing.hachimi.data.api`, `com.qing.hachimi.util` | Retain for now to preserve source provenance and avoid a broad mechanical rename. Plan an explicit package migration if desired. |
| Windows package / executable | `DesktopMusicCandidate` / `DesktopMusicCandidate.exe` | May receive DMC display branding in a coordinated release. Avoid changing executable identity if it breaks update or shortcut behavior. |
| Windows `upgradeUuid` / Inno `AppId` | `e1b24d8d-f6f8-429b-9f56-7d0c37c8f2b5` | Preserve this UUID so existing installations can upgrade in place. Do not generate a new one as part of a name-only change. |
| App data | `%APPDATA%\DesktopMusicCandidate`; fallback `~/.config/DesktopMusicCandidate` | Keep reading the current path. If adopting a DMC path, provide a one-time, tested migration that does not remove the old data before validation. |
| Credential Manager target | `DesktopMusicCandidate.Session.MUSIC_U` | Keep reading the current target. Credential migration must be explicit, user-scoped, secret-safe and independently tested. |
| Default download folder | `Music\DesktopMusicCandidate` | A display-name change must not silently redirect or strand existing downloads. |
| Logs | stderr prefix `[Desktop Music Candidate]`; no log file is created by this logger | Change the prefix with runtime branding; retain the existing redaction policy. |
| Window title / Start Menu group | `Desktop Music Candidate` | Change to DMC in the coordinated branding release. |
| Icon | `src/main/resources/dmc-icon.ico` | The preview already packages a DMC icon. Keep the icon independent of upstream Hachimi artwork. |

## Boundary

This document-only change updates the declared product name, not the installed application's identifiers. Before runtime renaming, verify an upgrade from the current Inno Setup installation, Start Menu and desktop shortcuts, user-selected install directory, app-data continuity, download destination, and Windows Credential Manager behavior. Keep the existing `upgradeUuid` and `AppId` stable unless a deliberate side-by-side product identity is approved.

DMC is an independent product derived in part from selected MIT-licensed Hachimi JVM API code. It is not presented as an official Hachimi desktop release. See [UPSTREAM.md](../UPSTREAM.md) for source attribution and [THIRD_PARTY.md](../THIRD_PARTY.md) for dependencies.
