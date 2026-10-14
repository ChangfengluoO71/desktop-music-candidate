# libmpv R2 Windows shared-output acceptance

## Purpose

This is an opt-in Windows device test. It loads the explicitly pinned local `libmpv-2.dll`, starts generated local audio at low volume, and verifies that mpv reports `wasapi` and that playback position advances for MP3, FLAC, AAC/M4A, and WAV. It never downloads media and never searches `PATH` for libmpv.

The ordinary `test` task excludes this test. It does not ship libmpv or change the installer.

## Run

Use a Windows desktop session with an available WASAPI shared playback device. In PowerShell, from the project root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon -PmpvLibrary='D:\path\to\libmpv-2.dll' -PmpvArchive='D:\path\to\pinned-mpv.zip' nativeMpvShared
```

The test emits `R2_NATIVE_ARTIFACT` with client API version and SHA-256 values, then one `R2_WASAPI_PLAYBACK` record per format. It fails if shared output does not activate, if playback position does not advance, or if the engine emits a playback error. The tone is played at 12% volume; use a safe listening level.

## Evidence record

| Item | Result |
|---|---|
| Windows build / audio endpoint | Windows 11 Pro build 26200; selected endpoint NOT_CAPTURED |
| libmpv client API | 2.5 |
| Development archive SHA-256 | `9FFBED7A8AA013DA07A925DFBB046116683796AF76A9B635E4E7B3328D44F9D5` |
| `libmpv-2.dll` SHA-256 | `FF1AAFF2CA9D5A56242DCEF28B20E3221B47F5FA38B25FBF6F3A8195E87157C1` |
| MP3: `current-ao` / position advanced | PASS - `wasapi`, 40 ms |
| FLAC: `current-ao` / position advanced | PASS - `wasapi`, 40 ms |
| AAC/M4A: `current-ao` / position advanced | PASS - `wasapi`, 23 ms |
| WAV: `current-ao` / position advanced | PASS - `wasapi`, 40 ms |
| Human confirmed audible output | PASS - user confirmed app playback on 2026-10-14; format and endpoint NOT_CAPTURED |
| Desktop app launch with production DLL | PASS - window opened; process had explicit DLL property and `libmpv-2.dll` loaded |
| Desktop player controls / queue / lyrics | NOT_TESTED |

**Automated runs:** the JDK 25 offline suite passed on 2026-10-13 with 274 tests, zero failures/errors/skips. The opt-in `nativeMpvShared` test passed separately with one test, zero failures. It played generated local audio at 12% volume. `current-ao=wasapi` and a moving position confirm the real libmpv output path for all four formats; they do not establish what a person heard.

**Defect found during this gate:** the first production-engine run exited while JNA read `mpv_event.eventId`. Production event structures were private nested JNA classes; the earlier decoder POC used accessible structures with explicit field-order annotations. The native worker failure was also being masked as "engine closed." The event structs are now accessible, explicitly ordered structures, and worker failures retain a sanitized cause. The same DLL then passed the shared-output run above.

**Concurrency review fix:** shutdown and queue draining now close the command mailbox atomically with command submission. This prevents a command from being inserted after the final drain and waiting forever. The regression test covers concurrent offer/stop, and the full suite includes these cases.

An active `current-ao=wasapi` and advancing playback position establish the Windows shared output path. The user separately confirmed audible playback in the app on 2026-10-14. The specific format, endpoint, and player controls used for that listening check were not captured.

## Manual app check

After the device test, launch the normal app with the explicit development DLL path:

```powershell
.\gradlew.bat --no-daemon -PmpvLibrary='D:\path\to\libmpv-2.dll' run
```

Use the local music page to play a known local MP3, FLAC, M4A, and WAV file. Check the play/pause/seek controls, queue advance, lyric display, and error reporting if the DLL or audio device is unavailable. Do not treat a successful app window launch alone as playback acceptance.
