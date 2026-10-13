# Desktop Music Candidate

An independent Windows desktop music application candidate for local music browsing and optional online music discovery/downloads.

This project is **not an official Hachimi desktop application**. It reuses a small number of MIT-licensed JVM API files from Hachimi and retains their original copyright and provenance. The candidate currently has a neutral placeholder name and has not been released.

## Current scope

- Local music folder scanning, SQLite indexing, metadata inspection and duplicate analysis.
- Optional NetEase discovery, search, collection import, QR session and download queue.
- Windows credential storage and HTTPS-constrained online transfer.
- There is no local audio playback engine yet. The local library can be indexed without login; playback, synced lyrics and an album poster wall are future product work.

The local library and online download/session stores are separate. The existing download model is still NetEase-specific; see [the architecture notes](docs/P0-ARCHITECTURE.md).

## Build and test

See [BUILDING.md](docs/BUILDING.md) for the JDK, test and Windows packaging commands. The project has its own Gradle Wrapper and does not build against `../app/`.

## Project notes

- [Upstream provenance](UPSTREAM.md)
- [Third-party dependency and license inventory](THIRD_PARTY.md)
- [Temporary product identity](docs/PRODUCT-IDENTITY.md)
- [P0 architecture and decoupling plan](docs/P0-ARCHITECTURE.md)
- [Product roadmap](docs/ROADMAP.md)
- [Private GitHub and CI preparation](docs/GITHUB_PRIVATE_PREP.md)
- [Pre-repository source and test file hash manifest](docs/PRE-REPO-MANIFEST-SHA256.txt)

## Branding

The name, repository slug, JVM namespace, Windows installer identity and icon are placeholders. No upstream Hachimi logo or restricted official identity is included. Do not describe this candidate as official or publish it under the Hachimi name.
