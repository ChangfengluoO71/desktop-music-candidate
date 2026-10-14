# R3 Integrated Player UX Design

**Status: Draft for user review**
**Scope:** DMC Windows desktop player and local-library experience. This document is a product and architecture design, not an implementation plan.

## 1. Goal

Make local playback feel like a coherent part of DMC. Playback controls, the library, album artwork, lyrics, and the queue should remain inside the main DMC window. The visual direction follows the user's earlier references: a responsive album-cover wall and an immersive now-playing view with artwork-led color, synchronized lyrics, and a compact control bar.

R3 keeps Kotlin, Compose Desktop, Miuix, and the existing libmpv audio backend. It does not replace the UI framework or launch the mpv command-line application.

## 2. Current-State Findings

- Production playback calls libmpv through JNA from `LibMpvPlaybackEngine`; source search found no `mpv.exe` process launch.
- `PlayerNowPlayingDialog` uses Compose `DialogWindow`, so the current full-player view is a second DMC top-level window. This is separate from the possible libmpv cover-art output window described below.
- `LibMpvPlaybackEngine.initializeClient` configures WASAPI but does not configure `audio-display`. mpv documents `embedded-first` as the default for audio playback; without an attached DMC video surface, embedded cover output may create a separate native window. This is a source-based hypothesis to verify with an embedded-art fixture, not a claimed live reproduction.
- `LocalMusicScreen` presents its album art in a short horizontal `LazyRow`, followed by filters and a track list. It has no full library browsing modes for albums, artists, and folders.
- `LocalArtworkThumbnail` and `PlayerArtwork` each construct a `LocalArtworkResolver`; embedded artwork is extracted with jaudiotagger and decoded for display without a shared bounded thumbnail cache.
- Library roots already support opt-in recursive monitoring. Bursts are debounced, but a change currently triggers a full scan across all configured roots rather than a path-level incremental update; retain this feature and improve how changes update the index.
- The lyric screen follows the current line and switches among local original, translation, and romanization variants. Lyric rows do not seek when clicked, and manual scrolling has no explicit pause/resume-follow behavior.
- The R2 Windows acceptance record confirms libmpv/WASAPI and audible playback, but the player controls, queue, lyrics, and installed-app UX remain untested.
- Legacy JavaFX playback source and tests remain in the tree even though the production entry point selects libmpv and the unavailable-backend path does not fall back to JavaFX. Remove this dead reference only after libmpv acceptance is complete.

## 3. Research Findings Applied to DMC

The research is used for interaction patterns only. No third-party source code or assets are to be copied.

- [Harmonoid](https://github.com/tamhgdc/harmonoid-music) documents tag-based album/artist grouping, cached library indexing, libmpv playback, synced lyrics, queue reordering, and Windows media controls. Its mini-window is listed as upcoming, so it is not evidence for a finished mini-player interaction.
- [Tauon Music Box](https://github.com/Taiko2k/Tauon) documents a cover-focused showcase and auto-following synced lyrics in its [lyrics guide](https://github.com/Taiko2k/Tauon/wiki/Lyrics/42fca080d12570fd1c8b9869aebc79a0d9946597). [limusic](https://github.com/SimoHypers/limusic) provides a closer interaction reference for a compact mini-player, a cover-and-lyrics theater view, lyric click-to-jump, and translated lines; it is a Tauri streaming client, so only interaction patterns apply.
- [fooyin](https://github.com/fooyin/fooyin) documents a [shared cover repository, thumbnail caching, and cache invalidation](https://github.com/fooyin/fooyin/blob/master/CHANGELOG.md), as well as explicit queue actions and synchronized lyric behaviors. DMC should take the cache and action clarity lessons without inheriting fooyin's broad customization surface.
- [Soul Searching](https://github.com/enteraname74/SoulSearching) and [LynMusic](https://github.com/wesley666/LynMusic) demonstrate Compose/Kotlin local-library patterns such as album/artist/song browsing, themes derived from cover art, and synchronized lyrics. Both are GPL-3.0 projects, so DMC should not copy their code.
- [Strawberry](https://github.com/strawberrymusicplayer/strawberry) illustrates how lyrics, artwork, library organization, and playlists can grow into a large feature set. Online lyric providers and extensive playlist management are deliberately deferred from R3.

## 4. Product Structure

### 4.1 Main-window navigation

Keep the existing DMC shell and its discovery, search, collections, downloads, local music, and settings destinations. Add an in-window now-playing route/state. Do not open a second top-level window for the player.

The local library becomes a focused browsing destination with four views:

- **Albums:** responsive cover grid; this is the default library view.
- **Artists:** artists and their album counts, opening a filtered artist page.
- **Songs:** searchable, sortable track list with format and duration.
- **Folders:** watched roots and subfolder browsing, with scan controls kept in a secondary toolbar or library settings area.

Search and filters remain available across the library views. Download/discovery pages stay reachable from the existing shell and are not redesigned in R3.

### 4.2 Persistent mini-player

Keep a compact player attached to the bottom of the main window. It displays artwork, title, artist, elapsed/total time, progress, play/pause, previous/next, and a control that opens the in-window now-playing route. Playback continues while the user changes pages.

### 4.3 In-window now-playing view

The now-playing route fills the main content area while retaining DMC's app identity and navigation. It is not a `DialogWindow` and does not create an mpv-owned window.

- Use the current track's artwork as the visual anchor. A dimmed, low-resolution or cached backdrop may supply a restrained gradient; text and controls must retain readable contrast.
- Place the large cover and track metadata beside a synchronized lyric panel on wide layouts. On narrow layouts, switch to a stacked layout and expose lyrics/queue as tabs or a side panel.
- Provide original, translation, and romanization modes when those local variants exist. Missing variants remain clearly unavailable; R3 does not synthesize them.
- Put the queue in a dismissible side panel on wide layouts and a separate tab/panel on narrow layouts.
- Keep transport controls and the seek bar in a stable lower control area.

## 5. Interaction Design

- Double-clicking a song starts it; explicit row actions support **Play now**, **Play next**, and **Add to queue**. Album actions can play or enqueue the album as one operation.
- Queue rows support clear current-track highlighting, drag reordering, remove, play-now, and clear-queue actions. Destructive actions require confirmation only when data could be lost; queue removal itself is reversible by re-adding.
- Clicking a timed lyric line seeks to its timestamp. When the user scrolls manually, automatic following pauses; a visible action resumes following. Track changes and lyric-variant changes restore automatic following.
- Existing keyboard shortcuts remain. Focus in search fields must continue to protect text entry from playback shortcuts.
- Playback errors remain in the app UI with actionable text; missing libmpv/device failures must never produce an unrelated media window or silently change backend.

## 6. Artwork and Rendering Performance

Add a shared local artwork repository for library cards and the player:

- Key entries by canonical media path and file identity metadata (at minimum size and modification time); invalidate when the file changes or the watcher reports a relevant update.
- Deduplicate concurrent extraction requests for the same track/album.
- Keep a bounded memory cache of display-sized thumbnails and decode on a background dispatcher. Do not retain full-resolution embedded artwork for every visible album indefinitely.
- Preserve the existing image format validation and size limits. Missing/unsupported artwork uses a stable placeholder.
- Use lazy, adaptive grids so window width and DPI change card counts without eagerly materializing the library.

## 7. libmpv Window Policy

DMC is an audio library and owns all cover display. Configure libmpv not to render audio-file cover art in a separate native video output (`audio-display=no`, as described in the [mpv manual](https://mpv.io/manual/stable/#options-audio-display); validate whether an additional no-video option is needed for tested fixtures). Do not disable TLS, alter audio output, launch `mpv.exe`, or attach an unowned native window.

Regression coverage must play generated audio fixtures with embedded artwork and verify that playback remains active while no extra top-level window is created. Continue to verify active WASAPI output and position progress for supported formats.

## 8. Delivery Order

Implement R3 as short, independently reviewable slices so the main playback-window problem is resolved before the library grows:

1. **R3-A: Integrated playback shell.** Move full now-playing into the main window, keep the mini-player stable across navigation, and set/verify libmpv's audio-only window policy. This addresses the current separate-window experience first.
2. **R3-B: Library browsing and artwork.** Build the responsive album wall and album/artist/song/folder views on the current local index; centralize thumbnail extraction, caching, invalidation, and background decoding.
3. **R3-C: Playback interactions.** Finish lyric seeking and manual-follow controls, clarify queue actions, and test keyboard/focus behavior.
4. **R3-D: Directory updates.** Preserve opt-in recursive watching, apply debounced file-level changes incrementally, wait for files to settle before reading tags, and fall back to a root scan after overflow or a missed-watch recovery.
5. **R3-E: Windows acceptance.** Exercise the integrated UI at standard and high DPI, with embedded-art audio fixtures and a large synthetic library.

Each slice must leave the previous slice usable and include a focused acceptance check. R3-D does not rebuild the existing watcher from scratch.

## 9. Scope Boundaries

### Included in R3

- Remove the separate now-playing `DialogWindow` in favor of the main-window route.
- Prevent libmpv from opening an audio cover-art output window.
- Responsive local library views and album-cover grid.
- Artwork extraction/thumbnail caching for the library and player.
- Synchronized lyric seeking and manual-scroll follow controls.
- Clear queue actions and stable playback controls across navigation.
- Incremental processing of watched-folder changes, with a full-scan recovery path for watcher overflow or recovery.
- Update P1, README, roadmap, and status documentation to reflect R2 and R3 accurately.
- Windows real-window acceptance at common and high-DPI sizes.

### Deferred to R4

- libmpv binary provenance, dependency and license audit.
- Bundling libmpv only if the exact build and all linked libraries pass distribution review; otherwise retain a documented external-runtime path.
- Inno Setup runtime packaging and installed-app validation.
- Removing JavaFX reference implementation, dependency, and tests after R3 acceptance.

### Later candidates, not R3 commitments

- Optional online synced-lyric lookup with manual match confirmation and an offline cache. No automatic translation or romanization generation.
- Windows system media controls, taskbar/notification integration, and tray behavior.
- Gapless playback, ReplayGain, crossfade, equalizer, and audio-device selection after the core player is stable.
- Favorites, play history, ratings, and smart playlists after basic library navigation has real usage feedback.
- Tag editing and artwork writing remain separate because current local-library behavior is read-only by design.

## 10. Acceptance Criteria

1. Starting an audio file with embedded cover art does not open a second mpv/native video window; WASAPI playback and seek position continue normally.
2. Opening the full player stays inside the main DMC window. Switching to another DMC page does not stop playback; returning restores current track/queue state.
3. The album grid adapts to window width and DPI and remains responsive while scrolling a representative large test library.
4. Artwork is not repeatedly extracted/decoded for unchanged tracks during ordinary recomposition and page navigation; cache invalidation works after a file changes.
5. Play now, play next, add to queue, queue reorder/remove, pause/resume, seek, volume, previous/next, shuffle, and repeat each have automated coverage and Windows GUI acceptance evidence.
6. Timed lyric click-to-seek works. Manual scroll pauses follow, and the explicit resume action restores it. Original/translation/romanization remain available when present.
7. Empty, missing-cover, unsupported-format, missing-runtime, and unavailable-device states remain readable and do not create extra windows.
8. Test on at least 1280×720 at 100% scale and a high-DPI setting (150% or 200%); verify no clipped controls, unusable queue, or unreadable lyric text.
9. With directory monitoring enabled, add, modify, rename, and remove files and verify only affected records update; a watcher overflow must recover with a full scan without corrupting the index or touching source audio.

## 11. Risks and Decisions to Preserve

- A robust cover wall depends on bounded decoding and caching; caching full-size bytes or bitmaps without a budget can cause memory growth.
- `audio-display=no` is the proposed libmpv option based on mpv's documented default; the embedded-art fixture is required to establish behavior in DMC's pinned runtime.
- The R2 source/runtime licensing question remains open. mpv's [copyright and license notes](https://github.com/mpv-player/mpv/blob/master/Copyright) explain that the default project license is GPL-2.0-or-later and LGPL builds exclude GPL-only components; this does not determine the license of DMC's exact binary or all linked dependencies.
- The project graph predates R2 and returned broad, noisy results; this design is based on current-source inspection and official project documentation.
- Existing `PLAYER-P1.md`, `ROADMAP.md`, and `PROJECT-STATUS.md` still contain JavaFX-era statements and must be reconciled before claiming P1 complete.
