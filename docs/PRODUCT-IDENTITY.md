# Temporary product identity

The P0 candidate uses a neutral placeholder so the code can build independently while the final public brand remains undecided. The placeholder must not be treated as the final product name.

| Identity surface | Candidate value | Finalization rule |
|---|---|---|
| User-visible app name | `Desktop Music Candidate` | Replace after the product name is selected. |
| GitHub repository slug | `desktop-music-candidate` (proposed) | Create as a private repository only after source/license review. |
| Gradle root name | `DesktopMusicCandidate` | Align with the chosen repository/product identity. |
| Gradle group | `org.example.desktopmusic` | Replace `org.example` with a namespace controlled by the product owner. |
| JVM packages | `com.qing.hachimi.desktop`, `com.qing.hachimi.data.api`, and `com.qing.hachimi.util` remain for source compatibility and provenance | Migrate only after selecting an owned reverse-domain namespace; do not mechanically rename before that decision. |
| Windows package name | `DesktopMusicCandidate` | Set the final installer/display identity before the first public release. |
| Windows `upgradeUuid` | `e1b24d8d-f6f8-429b-9f56-7d0c37c8f2b5` | Candidate-only identity. Keep stable for candidate upgrades; decide explicitly whether the public release adopts it or starts a new install identity. |
| App data | `%APPDATA%\DesktopMusicCandidate`; fallback `~/.config/DesktopMusicCandidate` | Any migration from the old Hachimi profile must be explicit and opt-in. |
| Credential Manager target | `DesktopMusicCandidate.Session.MUSIC_U` | Keep independent from the old credential target; no credentials are migrated. |
| Default download folder | `Music\DesktopMusicCandidate` | Decide user-facing folder name and migration behavior before public release. |
| Logs | stderr prefix `[Desktop Music Candidate]`; no log file is created by this logger | Choose final support/logging behavior and redaction retention rules before release. |
| Window title | `Desktop Music Candidate` | Align with final display name. |
| Default icon | Generic Compose/JDK packaging icon; no Hachimi icon copied | Design or commission an original icon before public release. |

The separate data directory and credential target prevent the candidate from opening or overwriting the existing Hachimi Desktop profile. This P0 does not migrate old JSON, SQLite, settings or credentials.
