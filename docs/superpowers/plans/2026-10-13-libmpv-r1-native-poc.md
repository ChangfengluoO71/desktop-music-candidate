# libmpv R1 Native POC Implementation Plan

> **For agentic workers:** Implement this plan inline, task by task. The R1 harness is test-only and must not alter production playback.

**Goal:** Prove that a pinned Windows x64 libmpv build can be loaded through JNA and can decode the existing local MP3, FLAC, WAV, and M4A fixtures while reporting usable playback events.

**Architecture:** Put all native bindings and the POC entry point under test sources. Load libmpv only from an explicitly supplied absolute DLL path; do not depend on PATH or ship the test DLL. Use libmpv's null audio output so this gate proves decoding and native event behavior without claiming audible device output.

**Tech Stack:** Kotlin/JVM 17 bytecode, JNA 5.19.1, JUnit 5, Gradle, the pinned first-party mpv Windows x64 LGPL development artifact.

**Spec:** `docs/superpowers/specs/2026-10-13-libmpv-playback-design.md` (R1 section)

## Global Constraints

- R1 only: do not change `Main.kt`, production `PlaybackCoordinator`/`PlaybackEvent`, player UI, or production dependencies.
- Keep `JavaFxPlaybackEngine` and all JavaFX dependencies during R1.
- Do not add the native DLL or its dependency files to Git, the application runtime, the installer, or a release.
- Load the explicitly configured absolute DLL path; never search arbitrary current-directory or PATH locations.
- Use `ao=null` only for offline decoder/event tests; it is not evidence of WASAPI playback.
- Run Gradle under the installed JDK 25; do not change the Gradle Wrapper configuration.
- Pinned POC asset: mpv first-party CI `git-release`, commit `c3ac7f39514c7f45bc38a18e66887240539ee33c`, Windows x86_64 LGPL development ZIP. Record downloaded archive and extracted DLL SHA-256 locally and in test output.

## Review Focus

- A wrong JNA structure layout can corrupt the process: integration assertions must read the actual API version, event IDs, and end-file reason from the real DLL.
- UTF-8/Unicode file paths can be mangled by Windows ANSI conversion: the test must load an audio fixture copied under a path containing Chinese characters and spaces.
- `loadfile` returns before media load: tests must wait for `MPV_EVENT_FILE_LOADED` and observe position advancement.
- Stop/quit/error must not be counted as natural EOF: test the real `MPV_EVENT_END_FILE.reason` values.
- Native handles or worker activity can leak: create and terminate repeated instances and require each to exit its event wait loop.

---

### Task 1: Native event and UTF-8 interop primitives

**Files:**
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvEventDecoding.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvEventDecodingTest.kt`

**Interfaces:**
- Produces `enum class NativeEndReason { EOF, STOP, QUIT, ERROR, REDIRECT, UNKNOWN }`.
- Produces `fun decodeNativeEndReason(reason: Int): NativeEndReason` using the pinned mpv client header values 0, 2, 3, 4, and 5; every other integer maps to `UNKNOWN`.
- Produces `data class NativeArgv(val pointers: Memory, private val strings: List<Memory>)` and `fun encodeNullTerminatedUtf8Arguments(arguments: List<String>): NativeArgv`. The pointer array contains UTF-8 NUL-terminated strings and a final null pointer; retaining `strings` keeps every pointed-to allocation alive through the native call.

- [x] Write tests for each reason mapping, unknown reason, and a Unicode path round trip through the encoded `char **` arguments. Add only a deliberately incorrect test-only stub so the test compiles.
- [x] Run `.\gradlew.bat --no-daemon -p . test --tests '*LibMpvEventDecodingTest'`; expected RED is assertion failures for the incorrect mappings/encoding, not compilation errors.
- [x] Implement only the two test-support helpers in `LibMpvEventDecoding.kt`.
- [x] Rerun the focused test; expected GREEN is all reason mappings and UTF-8 round-trip assertions passing.

### Task 2: Real native POC integration harness

**Files:**
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvNativePocTest.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvNativeApi.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/PcmWaveFixture.kt`
- Modify: `build.gradle.kts`

**Interfaces:**
- `LibMpvNativeApi` maps only the C API calls needed by the POC: `mpv_create`, `mpv_set_option_string`, `mpv_initialize`, `mpv_request_event`, `mpv_command`, `mpv_wait_event`, `mpv_event_name`, `mpv_client_api_version`, `mpv_get_property_string`, `mpv_free`, `mpv_error_string`, and `mpv_terminate_destroy`.
- `LibMpvNativePocTest` reads `dmc.libmpv.path`; it loads only that normalized absolute path using JNA. It never mutates production playback classes.
- Gradle task `nativeMpvPoc` consumes `-PmpvLibrary=<absolute DLL path>` and `-PmpvArchive=<absolute pinned ZIP path>`, records both hashes, and runs only tests tagged `native-mpv-poc`; regular `test` remains runnable without the native DLL.

- [x] Add the tagged integration contract first. It must assert successful native initialization, a minimum supported client API version, and per-format `FILE_LOADED`, positive duration, advancing position, and EOF events for MP3/FLAC/WAV/M4A. Keep a short-FLAC completion check and add a longer synthetic FLAC progress fixture because the existing 0.5-second FLAC reaches EOF before position polling can reliably observe advancement. Generate the PCM WAV fixture in a temporary directory. Add a test-only placeholder that reports the harness as not implemented.
- [x] Run `.\gradlew.bat --no-daemon -p . nativeMpvPoc -PmpvLibrary=<absolute DLL path> -PmpvArchive=<absolute ZIP path>`; expected RED is the placeholder's explicit failed assertion, proving the native-only task selects the test and can load its runtime classpath.
- [x] Implement the JNA interface, UTF-8 command marshaling, end-file structure decoding, null-output configuration, event wait loop with bounded timeout, and deterministic native cleanup needed by the test.
- [x] Rerun the focused native task against the pinned DLL and capture its first real result; any failures must be specific assertions or native diagnostics, not silent skips.
- [x] Add checks for stop/quit reason classification, a missing/corrupt local file not being reported as a successful decode, Unicode/space-containing path, and at least three create/load/terminate cycles.
- [x] Run `.\gradlew.bat -p . nativeMpvPoc -PmpvLibrary=<absolute path to libmpv-2.dll> -PmpvArchive=<absolute pinned ZIP path>` and confirm the real DLL passes all supported formats; record API version, build commit, archive hash, DLL hash, and per-format results.

### Task 3: Regression and R1 boundary verification

**Files:**
- Modify: `docs/superpowers/specs/2026-10-13-libmpv-playback-design.md`
- Modify: `docs/superpowers/plans/2026-10-13-libmpv-r1-native-poc.md`

- [x] Run `.\gradlew.bat --no-daemon -p . test --rerun-tasks` under JDK 25 and record passed, failed, and skipped counts.
- [x] Run `.\gradlew.bat --no-daemon -p . nativeMpvPoc -PmpvLibrary=<absolute path> -PmpvArchive=<absolute ZIP path>` under JDK 25 and record the real-native result.
- [x] Run `git diff --check`; verify no native DLL, extracted dependency, account data, or generated audio output is tracked.
- [x] Update only the R1 evidence/status in the design document. Keep R2/R3 explicitly gated and keep production JavaFX in place.

## R1 Completion Criteria

`NATIVE_POC_PASS` requires the real pinned x64 DLL to load through JNA by absolute path, initialization and repeated destruction to succeed, MP3/FLAC/WAV/M4A to load and advance playback position, and native event/error classification tests to pass. This status does not mean audible playback, production integration, installer support, exclusive-mode support, or license clearance for public redistribution.
