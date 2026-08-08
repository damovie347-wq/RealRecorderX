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
  recording never just fails outright.
- **Audio:** system playback capture (Android 10+) and the microphone are two
  *entirely separate* `AudioRecord` instances, mixed in-app with independent
  levels, a soft-knee limiter, optional voice-priority ducking, and platform
  echo cancellation / noise suppression on the mic path.
- **Controls:** the floating pause/stop bubble is a second, `FLAG_SECURE`
  window, never part of any view MediaProjection could capture — see
  `overlay/RecordingOverlayController.kt`.
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

## License

No license file is included — add one (MIT/Apache-2.0 are common choices for
a project like this) before treating this as open source.
