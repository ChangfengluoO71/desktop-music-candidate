# P0 architecture and minimum decoupling plan

## Current dependency picture

```mermaid
flowchart LR
  subgraph Local[Local library — works offline]
    Files[Music files and embedded tags] --> Scanner[DesktopMusicScanner]
    Scanner --> Tracks[LocalTrack keyed by normalized path]
    Tracks --> SQLite[LocalMusicStore SQLite]
    SQLite --> LocalUI[Local music UI and duplicate analysis]
  end

  subgraph Online[Optional NetEase features]
    Discovery[Search / discovery / collections] --> Service[DesktopService and NetEase API]
    Service --> SongRef[SongHit / Song ID]
    SongRef --> Queue[DownloadTaskManager]
    Queue --> Session[SessionManager]
    Session --> Credential[Windows Credential Manager]
    Queue --> Resolve[Source resolution]
    Resolve --> Transfer[AudioUrlPolicy / DesktopDownloader]
    Transfer --> FilesOut[Downloaded media files]
    Resolve --> Post[Online lyrics / tags preparation]
    Post --> FilesOut
  end

  FilesOut --> Scanner
  LocalUI -. "no player engine currently" .-> Playback[Future provider-free playback]
```

## Findings from the current code

- `LocalTrack` identity is based on normalized file path. Moving a file creates a new local identity. Its metadata, technical parameters and optional SHA-256 live in the local SQLite index.
- The `LocalMusicStore` tables contain filesystem roots, local tracks and root/path membership. They do not contain NetEase song IDs or login state.
- Local scanning, indexing and duplicate analysis do not depend on `SessionManager`, cookies or a live API. They can operate offline.
- There is no audio playback engine or playback state in the current source. Lyrics, romanization, translation-aware display and a poster-wall player are not present.
- `DownloadTask` requires a NetEase `songId`; search, collection import, source resolution and post-processing are NetEase-aware. Download task history is a versioned JSON store, separate from the local music SQLite database.
- QR session credentials are stored through a Windows Credential Manager target; they are not part of the local library schema. Local tracks remain available when the account is signed out.
- Downloaded files can later be discovered as ordinary local files by the scanner, but there is no provider-neutral library ID linking the downloaded task record to a library track.
- Lyrics and audio-tag preparation currently use online NetEase metadata. File scanning and tag reading itself is local. The local library and online account are operationally separate, though both are owned by the same application.

## Minimal decoupling sequence

1. Keep the local file/path identity and SQLite schema stable while adding playback. Start with a local playback interface that accepts a file URI and does not depend on online providers or account state.
2. Introduce stable provider-neutral `LibraryTrackId` values and a separate `ProviderTrackRef(providerId, remoteId)` mapping. Do not put provider IDs into the core local track schema as the primary key.
3. Evolve new download records to use `providerId` plus `remoteTrackId`, retaining a NetEase adapter for existing JSON history. Keep old schema readers and define an explicit migration before changing serialization.
4. Define provider-neutral lyrics and artwork records. Store source/provenance separately from the editable local tags, so local playback and user tags survive logout or API changes.
5. Keep authentication inside provider modules. The local scanner, playback engine, local library store and local lyric files must not request a session.

P0 intentionally makes no SQLite or JSON schema change. The independent DMC preview uses its own `DesktopMusicCandidate` data root, so it does not mutate the previous Hachimi Desktop profile.
