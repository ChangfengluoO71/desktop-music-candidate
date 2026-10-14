# DMC

DMC is an independent Windows desktop music application focused on a local music library, with optional online discovery and downloads.

## Current status

- Local music folder scanning, SQLite indexing, metadata inspection and duplicate analysis are implemented.
- Optional NetEase discovery, search, collection import, QR session, downloads, lyrics and artwork handling are implemented.
- Windows credential storage and HTTPS-constrained online transfer are implemented.
- The production local player uses libmpv with Windows WASAPI shared output for MP3, FLAC, AAC/M4A, and WAV. The runtime is not bundled in the current preview; configure an explicit absolute `libmpv-2.dll` path or place the runtime under the app-private `libmpv` folder in `%APPDATA%\DesktopMusicCandidate`. There is no JavaFX or null-output fallback. R3 adds in-window Now Playing, adaptive local-library views, shared bounded artwork caching, lyric seeking/follow controls, queue actions, and incremental watched-folder updates. Automated tests and the EXE build pass; current Windows GUI and pinned-runtime acceptance remain open. JavaFX implementation/dependencies remain as migration residue, not a production fallback. See [the Player P1 plan](docs/PLAYER-P1.md), [current project status](docs/PROJECT-STATUS.md), and [R2 Windows acceptance](docs/testing/libmpv-r2-windows-acceptance.md).

The local library and player operate on local files without login and remain independent of account/session state. The existing online download model is still NetEase-specific; see [the architecture notes](docs/P0-ARCHITECTURE.md).

## Build and test

See [BUILDING.md](docs/BUILDING.md) for JDK, test and Windows packaging commands. The project has its own Gradle Wrapper and does not build against `../app/`.

## Project documents

- [Current project status](docs/PROJECT-STATUS.md)
- [Product identity and migration boundary](docs/PRODUCT-IDENTITY.md)
- [Player P1 plan](docs/PLAYER-P1.md)
- [P0 architecture and decoupling notes](docs/P0-ARCHITECTURE.md)
- [Product roadmap](docs/ROADMAP.md)
- [Upstream provenance](UPSTREAM.md)
- [Third-party dependency and license inventory](THIRD_PARTY.md)
- [Repository and CI status](docs/GITHUB_PRIVATE_PREP.md)

## Branding

DMC is the selected product name. The current preview's executable, window title, installer identity, data directory and credential target still use the earlier `Desktop Music Candidate` identity. Those runtime identifiers are intentionally not renamed by this documentation-only change: changing install/data identity requires a coordinated migration plan. DMC is an independent project and is not an official Hachimi Desktop release. No restricted upstream logo or icon is used.
