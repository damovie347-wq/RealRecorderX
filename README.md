# RecorderX

A performance-first Android screen recorder: hardware-encoded only, zero-copy
capture pipeline, independent system/mic audio with a smart mixing engine, and
a settings UI that stays out of its own recordings.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full technical design —
codec fallback logic, the bitrate advisor, the pause/resume mechanism, the
compatibility plan back to Android 8, and what's genuinely finished vs. what a
"starting version" leaves for device testing.

## Build it via GitHub Actions (no local Android SDK needed)

This is the intended way to get an APK out of this repo:

1. Push this project to a GitHub repository.
2. Open the **Actions** tab → the **Android Build** workflow runs
   automatically on every push, or trigger it by hand with **Run workflow**
   (`workflow_dispatch`).
3. When the `debug` job finishes, open the run and download the
   **RecorderX-debug-apk** artifact at the bottom of the page. Unzip it to get
   `app-debug.apk`.
4. Install it on a device with `adb install app-debug.apk`, or just copy it
   over and open it.

That's the whole loop: push → Actions builds → APK is a downloadable artifact.
No external CI service, no self-hosted runner — just the two GitHub-hosted
jobs in [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml).

### Getting a signed release APK

The `release` job builds automatically on manual dispatch (or a push to
`main` — change the `if:` condition in the workflow if your default branch is
named something else). Without any secrets configured it still produces a
**debug-signed** release APK (shrunk, minified, but not Play-Store-signed) —
good enough to confirm the release build config itself works.

For a real, installable-anywhere release signature, add four
[repository secrets](../../settings/secrets/actions):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Your `.jks`/`.keystore` file, base64-encoded (`base64 -w0 your.keystore`) |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

None of these are ever written to source — `app/build.gradle.kts` only reads
them via `System.getenv(...)`, and the workflow decodes the keystore to a
gitignored temp file that's deleted at the end of the job regardless of
success or failure.

### Why there's no committed `gradlew` / `gradle-wrapper.jar`

The Gradle Wrapper's launcher scripts are plain text and easy to get exactly
right, but `gradle-wrapper.jar` is a compiled binary — hand-authoring binary
bytes isn't something that can be done reliably outside a real Gradle
install. Rather than commit a jar that might be silently corrupt, the
workflow's first two steps provision a real Gradle 9.5 via
[`gradle/actions/setup-gradle`](https://github.com/gradle/actions) and then
run `gradle wrapper` to generate a correct, version-matched wrapper fresh on
**every** run — which is also more robust than a stale committed one.

For local development, open the project in **Android Studio**: it detects
the missing wrapper and offers to generate it automatically. Or, with a
system Gradle install, just run:

```bash
gradle wrapper --gradle-version 9.5.0 --distribution-type all
```

## What this app actually does

- **Capture:** `MediaProjection` → `VirtualDisplay` → `MediaCodec`'s input
  `Surface`, directly. No `Bitmap`, no `ImageReader`, no CPU frame copies —
  SurfaceFlinger composites straight into the encoder's input surface via GPU.
- **Codec:** tries AV1 → HEVC → AVC hardware encoders in that order (capped by
  whatever the user picks, or by a device-tier-aware default — H.264 on
  Android 8/9 and lower-RAM devices), with a software-encoder last resort so
  recording never just fails outright. If **Software AV1** is explicitly
  turned on, a CPU-based AV1 encoder (AOSP's libaom-based path, where present)
  is tried right after hardware AV1 and before falling to HEVC/AVC — capped at
  1080p and a conservative fps, since software encode throughput is nowhere
  near a hardware block's. **Color depth** (8-bit / 10-bit) is also
  selectable; 10-bit only requests a genuine Main10-family encoder profile and
  falls back to 8-bit with a toast if nothing in the cascade can actually do
  it — see `codec/CodecSelector.kt`.
- **Audio:** system playback capture (Android 10+) and the microphone are two
  *entirely separate* `AudioRecord` instances, mixed in-app with independent
  levels, a soft-knee limiter, optional voice-priority ducking, platform echo
  cancellation / noise suppression on the mic path, and a second-stage
  correlation-based bleed suppressor (`audio/ResidualBleedSuppressor.kt`,
  strength adjustable: Off/Normal/Strong) for when system audio played
  through the phone's own speaker leaks back into the mic.
- **Controls:** the floating pause/stop bubble is a small, translucent,
  low-opacity-when-idle window — see `overlay/RecordingOverlayController.kt`
  for why it's deliberately *not* `FLAG_SECURE` by default (that renders as a
  solid black shape in the recording, not a clean omission). **Bubble
  Visibility** offers three modes: VISIBLE (default), AUTO-HIDE (detaches
  itself a few seconds after showing — reachable afterward from the
  notification's "Show controls" action or the Quick Settings tile), and
  BLACKOUT (opts back into `FLAG_SECURE` for anyone who'd rather have a small
  black shape than legible controls in their footage). No mode makes the
  bubble both live-visible *and* invisible to this app's own recording at
  once — see that file's kdoc for why no third-party app can do that.
- **Quick Settings tile:** `service/RecordingTileService.kt` adds a shade
  shortcut (add it once via the shade's "Edit tiles" pencil icon, like any
  third-party tile) — stops a running recording with no Activity needed, or
  opens the app when idle (starting needs the system consent dialog, which
  only an Activity can show).
- **Appearance:** Light / Dark / AMOLED, switchable from the THEME button in
  the top bar (`MainActivity#showThemeDialog`) — Dark uses standard DayNight
  resource qualifiers (`values-night/`), AMOLED layers true black on top of
  Dark's palette for the main surfaces and system bars.
- **UI:** one programmatically-built settings screen (no Compose, no XML
  layout for the main screen) using a single custom `Canvas`-drawn slider
  view for every control, matching the reference screenshot's pill sliders.

## Permissions this app requests, and why

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Both mic capture and system-audio playback capture are built on `AudioRecord` |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MICROPHONE` | Recording runs in a foreground `Service` so it survives leaving the app |
| `POST_NOTIFICATIONS` | Android 13+ requires opt-in to show the ongoing-recording notification |
| `SYSTEM_ALERT_WINDOW` | The floating control bubble is a "draw over other apps" window |
| `WRITE_EXTERNAL_STORAGE` (capped `maxSdkVersion=28`) | Only needed pre-scoped-storage; Android 10+ saves via `MediaStore` with no storage permission at all |

Screen-capture consent itself is the standard system dialog `MediaProjection`
triggers — there's no way around that prompt, by design of the platform.

## Project layout

```
app/src/main/kotlin/com/recorderx/app/
├── MainActivity.kt          entry point; builds the settings UI, drives the
│                             permission -> overlay -> projection-consent chain
├── App.kt                   minimal Application subclass
├── capture/                 MediaProjection -> VirtualDisplay wiring
├── codec/                   hardware-encoder discovery + AV1/HEVC/AVC fallback
├── encoder/                 MediaCodec <-> MediaMuxer plumbing (video, audio, muxer sync)
├── audio/                   system + mic capture, mixing, ducking
├── adaptive/                thermal-aware live bitrate/fps throttling
├── bitrate/                 the bits-per-pixel bitrate suggestion formula
├── service/                 the foreground Service orchestrating a session
├── overlay/                 the FLAG_SECURE recording-controls bubble
├── settings/                the settings model + SharedPreferences persistence
├── storage/                 MediaStore / legacy-file output resolution
├── ui/                      the one reusable pill-slider custom View
└── util/                    permissions, device-tier heuristics, resolution math
```

## Known limitations of this starting version

This is a complete, compiling, architecturally-real implementation — not
pseudocode — but a few things are explicitly out of scope for a first pass
and are called out in code comments where relevant:

- **Auto orientation** is resolved once, at the moment recording starts, not
  tracked live through a mid-recording device rotation.
- **DSP tuning** (ducking attack/release times, the speech-detection
  threshold, soft-clip knee) uses reasonable, documented starting constants —
  worth listening back and adjusting on real devices/content.
- **AV1 hardware encoders** are still rare industry-wide; on most devices the
  AV1→HEVC→AVC cascade will land on HEVC or AVC, which is expected, not a bug.
- No automated tests are included yet (a natural next addition once you're
  iterating on a physical device).

## Troubleshooting log

Real issues surfaced going from "compiles" to "actually runs correctly,"
kept here so a regression is easy to recognize instead of re-diagnosing from
scratch:

1. **CI: `sdkmanager` fails with `Failed to find package 'platforms;android-37'`.**
   Android 17 (API 37) has shipped, but Google hadn't published the
   `platforms;android-37` package to the `sdkmanager` repository yet at the
   time this was hit. Fix: `compileSdk`/`targetSdk` pinned to **36** in
   `app/build.gradle.kts` and `ANDROID_PLATFORM` pinned to `android-36` in
   the workflow, with comments at both spots marking them to bump back to 37
   once the platform package is actually published. `androidx.core` is
   correspondingly pinned to **1.17.0** — 1.18.0+ bakes a `minCompileSdk` of
   36.1/37 into its AAR metadata, which fails `checkDebugAarMetadata` against
   a 36 `compileSdk`.

2. **Build: a Kotlin K2 inference error on `savedEnumOrNull(KEY_CODEC) ?:
   CodecSelector.resolveDefaultPreference(...)`.** This is a real, currently
   open Kotlin compiler issue ([KT-86728](https://youtrack.jetbrains.com/issue/KT-86728),
   "reified type inference: expected type not propagated into inline call
   inside lambda with elvis operator") that AGP 9.3's bundled Kotlin 2.2.10
   hits. Fix: name the type argument explicitly —
   `savedEnumOrNull<VideoCodecOption>(KEY_CODEC)` — which sidesteps the
   inference path entirely. If you add another
   `inline fun <reified T> ... ?: fallback` pattern elsewhere, give it the
   same explicit type argument up front rather than waiting to hit this again.

3. **Runtime: app crashes immediately on launch, before any UI shows.**
   `MainActivity` used `com.google.android.material.materialswitch.MaterialSwitch`
   for the Floating Bubble Controls / Advanced Bitrate toggles.
   **`MaterialSwitch` requires a `Theme.Material3.*` parent theme** — it
   resolves `?attr/materialSwitchStyle` and Material-3-only color attributes
   (like `colorSurfaceContainerHighest`) that simply don't exist on
   `Theme.MaterialComponents` (the M2-family theme this app actually uses),
   so constructing it throws the moment `MainActivity.onCreate()` builds the
   settings screen — before the user can interact with anything at all. Fix:
   both toggles now use `androidx.appcompat.widget.SwitchCompat` instead,
   which works with any AppCompat/MaterialComponents theme and picks up the
   same yellow tint via `thumbTintList` (and, as a bonus, automatically
   through the theme's `colorControlActivated`). `MaterialButton` and
   `MaterialAlertDialogBuilder` do **not** have this requirement and were
   left as-is — only `MaterialSwitch` is Material-3-exclusive among the
   widgets this app uses.

If you ever hit a crash-on-launch again and the cause isn't obvious from
this list, the fastest path is a real stack trace:

```bash
adb logcat -c
adb shell am start -n com.recorderx.app.debug/com.recorderx.app.MainActivity
adb logcat *:E | grep -A 30 "FATAL EXCEPTION"
```

4. **Recorded video's duration shows garbage (e.g. "222:03:25") in players/
   gallery apps.** Surface-input frames arrive with whatever timestamp
   SurfaceFlinger stamped on them -- nanoseconds *since boot*, not since the
   recording started. Left unrebased, the video track's PTS values were
   enormous (hours), which is exactly what corrupts the duration players
   compute. Fix: `VideoEncoderPipeline` now rebases every frame's PTS against
   the session's first real frame, and separately absorbs each pause/resume
   gap (`requestPauseRebase()`) so the timeline stays continuous instead of
   jumping forward by however long a pause actually lasted in wall-clock time.

5. **Pausing a recording silently kills the audio track for the rest of the
   session.** `AudioMixEngine`'s mix loop called `audioEncoder.requestStop()`
   in a `finally` block that ran on *every* `stop()` -- including a pause,
   not just the final stop -- permanently EOS'ing the long-lived
   `AudioEncoderPipeline` that's meant to survive across a resume. Fix:
   that call was removed from the loop entirely; `RecordingService.handleStop()`
   already signals the real, final EOS explicitly exactly once. A matching
   `startFrameOffset` was added so a resumed `AudioMixEngine` continues the
   PTS timeline instead of restarting audio timestamps at 0 (which would
   have overlapped the pre-pause audio).

6. **Selected Resolution/FPS/Codec don't match the actual output file.**
   Three separate causes, all fixed together:
   - *Resolution:* `ResolutionResolver` used to silently substitute the
     panel's native size whenever a requested tier (e.g. "4K") exceeded it.
     Technically defensible (upscaling adds no real detail) but meant the
     picker had no effect with no indication why. It now always honors the
     exact selection -- `MediaProjection.createVirtualDisplay` can target any
     size regardless of the physical panel -- and exposes `isUpscaling()` for
     an informational UI note instead of an override.
   - *FPS:* `MediaFormat.KEY_FRAME_RATE` is only a bitrate-calculation hint on
     Surface input, not an enforced cap -- SurfaceFlinger keeps delivering
     frames at the display's native refresh rate regardless of it. The actual
     mechanism, `KEY_MAX_FPS_TO_ENCODER` ("max-fps-to-encoder"), takes a
     **float**, not an int; an earlier version stored it as an int, which is
     silently ignored (`Bundle` lookups are type-specific), so the cap never
     applied. Now stored as a float and applied at `configure()` time, not
     just reactively under thermal stress.
   - *Codec:* traced back to the same `inline fun <reified T>` settings-
     persistence pattern that hit KT-86728 (#2 above) -- the saved codec
     preference wasn't reliably read back, so sessions kept falling through
     to the AV1-cascade smart default (→ HEVC on most devices) regardless of
     what was selected. Fixed by the same rewrite. `RecordingService` now
     also toasts the *actually resolved* codec/resolution/fps at the start of
     every recording, and explicitly says so when a fallback happens (e.g.
     "AV1 isn't supported on this device — recording with HEVC instead")
     instead of substituting silently.

7. **No audio captured at all, despite every permission being granted.**
   `MicAudioSource` tried `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
   first, reasoning it'd get better built-in echo cancellation. That source
   is designed around two-way call audio and expects `AudioManager`'s mode to
   be set to `MODE_IN_COMMUNICATION` to route correctly -- something this app
   never did (deliberately: changing system-wide audio routing while
   recording is a bigger side effect than a screen recorder should cause). On
   some OEM audio HALs this source reports `STATE_INITIALIZED` successfully
   while actually capturing silence, which no initialization-only health
   check could catch. Fixed by using plain `AudioSource.MIC` instead, with
   `AcousticEchoCanceler`/`NoiseSuppressor` attached explicitly (they don't
   have this coupling). `AudioMixEngine` also now logs every step of both
   capture paths and exposes `hasSystemAudio`/`hasMicAudio` so
   `RecordingService` can toast a clear explanation whenever what was
   captured doesn't match what was requested, rather than a silently quiet
   recording.

8. **"High resolution/bitrate picked, still not sharp, especially partway
   through a long gaming session."** No single cause, and one of them isn't
   fixable — see `ARCHITECTURE.md` §11 for what earlier rounds already
   addressed (profile/level, the actual fps-capability mismatch, real
   upscaling-from-panel disclosure). What was still missing: `ThermalBitrateGovernor`
   silently cuts the live bitrate under real thermal pressure (by design —
   it's the actual mechanism keeping the device from overheating during a
   long, hot recording), but nothing ever told the user *why* a recording
   got visibly softer partway through, which reads exactly like "I picked
   high quality and it's still not sharp" if you don't know the device
   warmed up. `RecordingService` now toasts when combined throttling crosses
   a real threshold, and again when it fully recovers.
9. **"System audio + mic still echoes/doubles, especially loud transients
   like an explosion."** `ResidualBleedSuppressor`'s original tuning (20dB
   ceiling, 60ms attack) was too gentle for a loud, percussive sound played
   through the phone's own speaker with no headset — the attack was slow
   enough that a short transient was already mostly through before
   suppression caught up. Retuned (26dB/35ms at the new NORMAL default, 34dB/
   20ms at the new STRONG option a person can pick if their content/device
   still isn't clean) and exposed as a Mic Bleed Suppression setting instead
   of one fixed, unadjustable value. `RecordingService` also now suggests a
   headset once (not every session) when it detects Sys+Mic recording with no
   external audio output connected — the bleed's actual physical source,
   which software suppression can only clean up after the fact, not remove.
10. **"Recording controls appear live but shouldn't be baked into the video,
    like Samsung's recorder."** Confirmed again, not just repeated: still no
    public API lets a normal app keep its own overlay both live-visible and
    cleanly excluded from its own `MediaProjection` capture — see
    `ARCHITECTURE.md` §7. What's new is giving the person the actual trade-off
    directly instead of one fixed compromise: a **Bubble Visibility** setting
    (`OverlayVisibilityMode`) with VISIBLE / AUTO-HIDE / BLACKOUT, so whoever's
    recording can pick which cost they'd rather pay.
11. **"AV1 recording does nothing on a device without hardware AV1."**
    `CodecSelector.findBestEncoder`'s cascade only ever searched for a
    *hardware* encoder at every mime, and its one non-hardware attempt was
    always AVC — so a device with a genuine software AV1 path (AOSP's
    libaom-based encoder, present on Android 14+ with an updated media
    module) was never actually tried. A new, explicitly opt-in **Software AV1**
    setting inserts that missing attempt right after hardware AV1, capped at
    1080p and a conservative fps to stay realistic about CPU-bound encode
    throughput. A separate **Color Depth** (8-bit/10-bit) setting was added
    alongside it, requesting a genuine Main10-family encoder profile and
    falling back to 8-bit (with a toast) rather than silently recording 8-bit
    content under a 10-bit label.

That output pinpoints the exact class/line, which turns "the app crashes"
into a five-minute fix instead of a guessing game.

## License

No license file is included — add one (MIT/Apache-2.0 are common choices for
a project like this) before treating this as open source.
