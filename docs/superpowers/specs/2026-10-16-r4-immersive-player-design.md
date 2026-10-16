# R4 Immersive Player Design

**Status: Implemented; Windows manual acceptance is partial**
**Scope:** DMC Windows desktop immersive now-playing experience. This document defines product behavior and architecture; it is not an implementation plan.

## 1. Goal

Give local playback a polished, artwork-led listening view with readable synchronized lyrics and dependable controls. Keep playback inside the existing DMC main window and retain Kotlin, Compose Desktop, Miuix, libmpv, `PlaybackCoordinator`, `PlayerViewModel`, `LocalLyricsLoader`, and `LocalArtworkRepository`.

## 2. Verified baseline

- `NowPlayingScreen` is already rendered as a route inside the main window; playback is not implemented by opening a separate mpv application window.
- The regular now-playing page currently places transport/artwork and lyrics side by side on wide layouts, and stacks them on compact layouts.
- The mini-player persists across ordinary navigation and exposes previous, play/pause, next, seek, and now-playing navigation.
- Lyrics are loaded from embedded tags and local `.lrc`, `.tl.lrc`, and `.roma.lrc` files. Each variant is an independent timestamp sequence.
- Timed lyrics already support click-to-seek and pause/resume-follow after manual scrolling. The current active-line effect runs when the active line changes, not on every playback progress update; its current placement starts at the list's leading edge.
- Artwork decoding is shared and bounded to 64 MiB of decoded bitmap cache. Images are decoded off the UI dispatcher and limited to 1,024 px per edge.

## 3. Experience and navigation

### 3.1 Immersive mode

- Add an explicit **沉浸播放** action to the regular now-playing page and mini-player entry path.
- Immersive mode is a state of the existing main `Window`; do not create a second player window or a second playback engine.
- It hides the app sidebar, regular page heading, and mini-player while active. Its own transport controls remain visible.
- Provide visible exit/full-screen controls and `Esc` to return to the regular DMC shell. If immersive mode uses Windows full-screen placement, preserve and restore the main window's prior placement and size.
- Entering and leaving immersive mode must not stop or restart playback, mutate the queue, or clear the current lyric-follow preference.

### 3.2 Layout choices

Provide two compactly selectable layouts:

1. **封面 + 歌词** — large artwork and metadata on one side, lyrics on the other. This is the default on wide windows.
2. **歌词优先** — larger lyric region with a smaller cover and metadata. This is the default on compact windows and can be selected on wide windows.

A cover-only mode is deferred until the first two layouts pass readability and window-size acceptance.

## 4. Visual system

### 4.1 Artwork atmosphere

- Use the current track's embedded/local artwork as the source for the backdrop and foreground cover.
- Prepare a low-resolution backdrop (target long edge 128–256 px) and any color/blur treatment off the UI thread. Cache by canonical track path and file identity (size and modified time), and invalidate with the existing artwork cache rules.
- Never apply a full-resolution blur or decode a new image on each playback tick. Cross-fade only when the track/artwork changes; support a reduced-motion preference that replaces the cross-fade with an immediate switch.
- Keep a stable contrast veil/gradient above the backdrop. Lyric and control contrast must remain readable even for bright white covers and both application themes.
- Missing, corrupt, or unsupported artwork uses a coordinated DMC gradient/placeholder and must not prevent playback or lyrics.
- Keep the existing shared decoded-image memory ceiling; include backdrop images in that bounded budget.

### 4.2 Lyrics hierarchy

- Keep nearby lines readable. Establish hierarchy mainly through position, restrained opacity/color, and spacing; enlarge the active line moderately rather than shrinking every inactive line.
- Place the active line in a stable readable area around the upper-middle third of the lyric pane. Animate only when the active lyric line changes, the track changes, or the lyric variant/layout changes.
- Manual scrolling pauses automatic follow. A visible **恢复跟随** action resumes it; changing tracks resets follow to enabled.
- Clicking a timed line seeks to its timestamp. Lyrics with no usable timestamps remain readable but are not presented as seekable.

### 4.3 Translation and romanization alignment

- Do not pair lyric variants by list index. `original`, `translation`, and `romanization` are separate timestamped sequences.
- Build display groups using chronological, one-to-one timestamp matching with a bounded tolerance. Start with a configurable implementation threshold no greater than 500 ms; validate against fixtures before fixing the production value.
- A translation or romanization line can be attached to at most one original line. Do not force ambiguous, repeated, unmatched, or out-of-tolerance lines into the active group.
- If alignment is not trustworthy, retain the existing independent variant selector and display the chosen sequence normally. Never hide a valid lyric because another variant could not be aligned.
- The current original line is the main lyric. Matched translation and romanization are smaller secondary lines below it, with clear language/variant labels when ambiguity is possible.
- Do not fetch, synthesize, translate, or transliterate lyrics in R4.

### 4.4 Controls and queue

- Use a stable bottom control deck with previous, prominent play/pause, next, elapsed/total time, and seek bar.
- Keep shuffle, repeat, and volume available as secondary controls with visible selected states.
- Provide a dismissible queue panel that does not cover the active lyric by default. Queue actions continue to use the existing coordinator and do not create separate playback state.
- Preserve the mini-player and its controls in the regular application shell. It is hidden only while immersive mode is active.
- Preserve keyboard shortcuts and text-field focus protection; controls must also remain reachable by keyboard.

## 5. Responsive layout and accessibility

- Adapt at narrow widths and short heights without clipping metadata, transport buttons, lyrics, or exit controls.
- Verify at 100%, 150%, and 200% Windows scaling. Font sizes remain readable and interactive targets remain usable.
- Maintain WCAG-style text contrast against variable artwork using an opaque-enough overlay; do not derive text color from artwork without contrast correction.
- Reduced motion must disable backdrop cross-fades and nonessential lyric movement.
- Keyboard focus must remain visible, and `Esc` must be able to exit the immersive view without stealing ordinary text-editing behavior.

## 6. Acceptance cases

- Enter/exit immersive mode while playing, paused, loading, and while the queue is nonempty; playback position and queue remain unchanged.
- Test both layouts with wide, compact, short-height, and high-DPI windows.
- Test embedded artwork, sidecar artwork, missing artwork, corrupt artwork, and artwork file replacement while the library watcher is active.
- Test original-only lyrics; all three variants aligned; variants with small timestamp skew; unmatched and ambiguous lines; repeated lines; malformed LRC; and lyric files with offsets.
- Verify the active line stays in the intended reading area, scrolls only on line transitions, and does not jitter on progress updates.
- Verify click-to-seek, manual scroll pause, resume-follow, queue panel, keyboard navigation, and focus safety.
- Confirm artwork processing does not block playback controls and stays within the shared decoded-image cache budget.

## 7. Out of scope

- Replacing libmpv, changing audio output, or adding a second playback engine.
- Online lyric providers, translation generation, word-level karaoke timing, visualizers, casting, and multi-screen output.
- Redesigning discovery, downloads, settings, or the full local-library shell.

## 8. Suggested delivery slices

1. R4-A: artwork-derived backdrop, contrast treatment, and two responsive lyric/cover layouts.
2. R4-B: timestamp-grouped lyric presentation, central active-line placement, and reduced-motion behavior.
3. R4-C: immersive entry/exit, full-screen restoration, queue interaction, keyboard/focus acceptance, and Windows DPI verification.

## 9. R4 delivery and verification record (2026-10-16)

- Implemented timestamp-aligned lyric groups, bounded 256 px artwork backdrops, cross-fade/reduced-motion behavior, two immersive layouts, queue and playback controls, mini-player and now-playing entry points, Escape exit, and fullscreen placement restoration in the existing window.
- JDK 25 focused tests passed for `ImmersiveWindowStateTest`, `PlayerKeyboardShortcutsTest`, `PlayerScreensTest`, and `DownloadTaskStoreTest`. Full `test` task passed: 316 tests, 0 failures, 0 errors, 0 skipped.
- A real Windows `run` window was opened. The mini-player entry, cover-and-lyrics layout, lyrics-first layout, fullscreen toggle, and return to the regular shell were clicked and observed. The same track and cover remained visible after returning. This was an existing local-library track; no download or account operation was performed.
- 100%, 150%, and 200% Windows scaling, narrow/short-window clipping, active audio playback continuity, queue preservation with a populated queue, reduced-motion rendering, and missing/corrupt artwork were not manually exercised in the real window. Automated tests do not substitute for these checks.
