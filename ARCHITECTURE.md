# RecorderX — Architecture

## 1. High-level architecture

Three independent pipelines feed one muxer:

```
                     ┌─────────────────────────────┐
                     │      MediaProjection         │
                     │   (screen-capture consent)   │
                     └──────────────┬────────────────┘
                                    │
                   ┌────────────────┼─────────────────────┐
                   ▼                                       ▼
        ┌─────────────────────┐                 ┌───────────────────────────┐
        │   VirtualDisplay     │                 │ AudioPlaybackCapture      │
        │  (mirrors screen)    │                 │  Configuration (API 29+)  │
        └──────────┬───────────┘                 └─────────────┬─────────────┘
                   │ GPU composite, zero-copy                  │ system audio
                   ▼                                            ▼
        ┌─────────────────────┐                 ┌───────────────────────────┐
        │  MediaCodec (video)  │                 │      AudioMixEngine        │
        │  Surface input       │                 │  (+ MicAudioSource, AEC/NS,│
        │  hardware encoder     │                 │   ducking, soft limiter)   │
        └──────────┬───────────┘                 └─────────────┬─────────────┘
                   │ encoded H.264/H.265/AV1                    │ mixed PCM16
                   │                                            ▼
                   │                              ┌───────────────────────────┐
                   │                              │  MediaCodec (audio, AAC)   │
                   │                              └─────────────┬─────────────┘
                   │                                            │ encoded AAC
                   ▼                                            ▼
              ┌───────────────────────────────────────────────────┐
              │                    MuxerController                  │
              │   (waits for every expected track before start())   │
              └────────────────────────┬──────────────────────────┘
                                        ▼
                              MediaMuxer → .mp4 file
                        (MediaStore Movies/RecorderX, or
                         a legacy public file on API 26-28)
```

`RecordingService` (a foreground `Service`) owns and wires all of this
together; `MainActivity` never touches MediaCodec/MediaProjection directly —
it only resolves settings + capture dimensions and hands the service a plain
`Intent`. That separation is what lets recording survive the user leaving the
app, and is why pause/resume, thermal throttling, and the overlay bubble all
live in `service/`, `adaptive/`, and `overlay/` rather than in the Activity.

## 2. UI design

The settings screen is one `ScrollView` over a programmatically-built
`LinearLayout` (see `MainActivity#populateSettings`) — no XML layout, no
Compose. Every slider (codec, resolution, fps, bitrate, the 0-200% level
sliders, everything) is the same `SegmentedSliderView`: a `Canvas`-drawn pill
track, a yellow fill up to the thumb, small tick dots per step, a black
thumb. One class, reused ~14 times, driven entirely by data (a list of step
labels + a starting index) rather than N near-duplicate custom views.

Deliberately **not** Compose: the spec's own top priorities are minimal CPU
use, minimal APK size, and a "hafif" (light) UI. The Compose runtime and
tooling add real megabytes and real cold-start cost that a settings screen
built from `TextView`/`LinearLayout`/one custom `View` simply doesn't pay.
Material Components is used only for `MaterialButton`, `MaterialSwitch`, and
the guide dialog — small, well-optimized, and already a near-universal
Android dependency.

## 3. Kotlin code skeleton

See the file map in `README.md`. The short version: `settings/` is the only
thing every other package depends on (it's pure data — enums with a `label`
+ a `RecordingSettings` data class); everything else is one focused class per
concern, wired together by `RecordingService`.

## 4. MediaProjection + MediaCodec + MediaMuxer flow

1. `MainActivity` requests `MediaProjectionManager.createScreenCaptureIntent()`
   through the modern Activity Result API, **after** runtime permissions and
   the optional overlay permission are already resolved.
2. On consent, `MainActivity` resolves the actual capture pixel size
   (`util/ResolutionResolver`, run from the Activity because
   `Context.getDisplay()` throws on a plain Service context on API 30+) and
   starts `RecordingService` with the consent `resultCode`/`Intent` plus that
   size as plain `Intent` extras.
3. `RecordingService.beginPipelines()`:
   - Opens the output target (`storage/RecordingOutputResolver`) and gets a
     raw `FileDescriptor` — `MediaMuxer` writes directly into it, whether
     that's a `MediaStore`-provided descriptor (API 29+) or a legacy public
     file (API 26-28).
   - Picks a hardware encoder (`codec/CodecSelector`) and computes the
     bitrate (`bitrate/BitrateAdvisor`, unless the user picked a fixed value).
   - `encoder/VideoEncoderPipeline.configure()` configures `MediaCodec` with
     `COLOR_FormatSurface` and returns `createInputSurface()` — **this is the
     zero-copy handoff.**
   - `capture/ScreenCaptureController.start()` calls
     `mediaProjection.createVirtualDisplay(...)` with that exact `Surface` as
     the target. From this point, SurfaceFlinger writes frames straight into
     the encoder; nothing in this codebase ever reads a pixel.
   - If audio is enabled, `encoder/AudioEncoderPipeline` (AAC, `ByteBuffer`
     input this time — there's no Surface concept for audio) and
     `audio/AudioMixEngine` are started the same way.
4. Both encoders drain on their own dedicated threads
   (`Process.THREAD_PRIORITY_FOREGROUND` for video,
   `THREAD_PRIORITY_URGENT_AUDIO` for audio — "encoder thread'ini ayrı
   yönet" is literal here, not just conceptual separation). Each drain loop's
   `INFO_OUTPUT_FORMAT_CHANGED` result registers a track on the shared
   `MuxerController`, which only calls `MediaMuxer.start()` once every
   expected track (1 or 2) has registered — see the kdoc on
   `MuxerController` for why that gate has to exist at all.
5. Stopping calls `signalEndOfInputStream()` on the video codec (the correct
   EOS mechanism for Surface input) and queues an EOS buffer on the audio
   codec (the correct mechanism for ByteBuffer input), waits for both drain
   threads to actually finish, then releases the muxer and finalizes the
   output file (clearing `MediaStore`'s `IS_PENDING` flag, or running the
   legacy file through `MediaScannerConnection` so it gets a proper content
   `Uri`).

### Pause/resume

There's no native "pause" on `MediaCodec`/`MediaProjection`. Pausing releases
*only* the `VirtualDisplay` (and the audio sources) — the encoders, the
muxer, and the encoder's input `Surface` all stay alive. Resuming recreates
the `VirtualDisplay` pointed at that same still-valid `Surface`, so frames
simply resume flowing with no new track, no file-segment stitching, and no
PTS discontinuity to reason about. See the kdoc on
`RecordingService#pauseInternal`.

## 5. Codec fallback logic

`codec/CodecSelector.findBestEncoder()` walks a cascade implied by the user's
selection — picking **AV1** means "run the full AV1 → HEVC → AVC cascade,"
picking **H.265** means "HEVC → AVC," picking **H.264** pins AVC only. Each
step queries `MediaCodecList` for encoders that both declare the mime type
*and* pass a hardware check (`MediaCodecInfo.isHardwareAccelerated()` on API
29+, or the `OMX.google.*`/`c2.android.*` naming heuristic below that — the
same heuristic the wider Android ecosystem used before that API existed).
Each candidate's width/height is rounded to the codec's required alignment
and clamped into its supported range before being accepted, so `configure()`
never throws on an odd panel size like 1080×2412. Each candidate also reports
back the highest (profile, level) pair *it* actually advertises
(`CodecSelector.pickProfileLevel`) preferring a profile that unlocks the
codec's full toolset (AVC High / HEVC Main / AV1 Main8) — `VideoEncoderPipeline.configure()`
sets these explicitly via `KEY_PROFILE`/`KEY_LEVEL` rather than leaving them
to the driver's own default, since several vendor drivers silently
`configure()` into their *lowest* profile/level when the format doesn't ask
for one, capping both the encoding toolset available and, via the paired
level, the maximum bitrate the stream is even allowed to reach regardless of
`KEY_BIT_RATE`. This is the actual mechanism behind "bitrate ve çözünürlüğü
yükselttim ama görüntü hâlâ net değil" independent of anything upscaling-related
(§6) — the two are separate, stacking causes of the same symptom, not
alternate explanations of it. If literally nothing hardware-backed matches
anywhere in the cascade, the absolute last resort is *any* AVC encoder,
software included — recording still works, just hotter; `RecordingService`
surfaces a toast when this happens.

The *default* selection itself is device-aware
(`CodecSelector.resolveDefaultPreference`): Android 8/9 or a device this app
classifies as low-tier (`util/DeviceTier`, a simple RAM+core-count heuristic)
defaults to H.264; everything else defaults to the full AV1 cascade, so
capable devices get the most efficient codec automatically while older/
weaker ones get the safest one, matching the spec exactly.

## 6. Bitrate & resolution suggestion system

`bitrate/BitrateAdvisor` uses the standard bits-per-pixel-per-frame estimate:
`target_bps = width * height * fps * bpp(codec)`, with `bpp` tuned per codec
efficiency (AV1 needs meaningfully fewer bits than AVC for equivalent
quality; HEVC sits in between) — a long-standing rule of thumb in video
encoding, not anything proprietary. It's keyed off the *actually resolved*
encoder mime type, not the user's preference, so a fallback (AV1 requested,
AVC hardware actually used) is reflected honestly in the suggestion.

This is the *static*, pre-recording half of "efficient bitrate management."
The *runtime* half is simpler than it might sound: choosing **VBR** (the
spec's own default) means the hardware encoder itself already allocates more
bits to high-motion frames and fewer to static ones within the target
average — which is exactly "hareketli sahnelerde bitrate artırılsın, statik
sahnelerde azaltılsın" without any extra machinery. `adaptive/
ThermalBitrateGovernor` is the one thing that *actively* changes the target
bitrate mid-recording, and it does so for thermal reasons (see §8) via
`MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`, which most hardware encoders honor
as a live parameter change.

Resolution requests are resolved against the device's *real* panel size
(`util/ResolutionResolver`) but always honor the user's exact pick rather
than silently capping it — `MediaProjection.createVirtualDisplay` can target
any width/height regardless of the physical panel (the platform scales the
mirrored content to fit), so there's no *technical* reason to override "4K"
on a 1080p panel into plain 1080p. There is, however, a reason to *disclose*
it: doing that scales up from fewer real source pixels than the label
implies, which is a second, independent explanation for "bitrate ve
çözünürlüğü yükselttim ama görüntü hâlâ net değil" alongside the profile/level
one in §5 — `ResolutionResolver.isUpscaling()` drives a note under the
Resolution slider in `MainActivity` whenever the current pick would upscale,
naming the panel's actual native resolution so the choice stays informed
without silently substituting a different one.

## 7. Recording-invisible control panel

`overlay/RecordingOverlayController` shows the pause/stop bubble as a
**second window**, added via `WindowManager` with
`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.

An earlier version also set `FLAG_SECURE` on that window, reasoning that
it's the platform mechanism for excluding a window from non-secure capture
destinations. In practice `FLAG_SECURE`'s documented, standard behavior in a
capture — screenshot, cast, or a `MediaProjection` `VirtualDisplay` (including
the one this app itself creates) — is to render the secure window's bounds as
a **solid black shape**, not to cleanly omit it and reveal whatever's
underneath. That's correct for `FLAG_SECURE`'s actual purpose (guarantee a
banking PIN pad can never leak into any capture path, full stop) and wrong
for this one, and it's what the "siyah bir nokta" reports turned out to be:
the platform doing exactly what `FLAG_SECURE` documents, not an OEM bug.

There is no public API that lets a normal, non-privileged app make its own
overlay simultaneously (a) visible live on screen and (b) cleanly excluded —
not blacked out — from that same app's own `MediaProjection` recording.
Samsung's recorder achieves it because it's a privileged system component
with capture-pipeline access no third-party APK is granted; mainstream
third-party recorders (XRecorder, ADV Screen Recorder, etc.) don't fake this
either, and ship the same combination this app now does:

1. **No `FLAG_SECURE`.** The window is an ordinary, small, translucent
   overlay — a ~16dp dot at rest — so there is no black shape under any OEM
   compositor, ever. It's small and low-opacity by design specifically
   *because* it can end up in a frame now.
2. **A real hide action** (the eye icon on the expanded row) that fully
   detaches the window from `WindowManager` — actually removed, not shrunk
   or made transparent — for whenever a completely clean frame matters more
   than having the controls reachable on-screen. `RecordingService` adds a
   "Show controls" action to the persistent recording notification while
   hidden this way, since the bubble can't offer its own way back once it's
   gone.

## 8. Android 8+ compatibility plan

| Feature | API 26-28 (Android 8/9) | API 29+ (Android 10+) |
|---|---|---|
| Video capture | `MediaProjection` + `VirtualDisplay` (unchanged since API 21) | same |
| Hardware encoder detection | Name-heuristic (`OMX.google.*` = software) | `MediaCodecInfo.isHardwareAccelerated()` |
| System audio capture | **Not available** — recording proceeds mic-only if Audio Source needs system sound | `AudioPlaybackCaptureConfiguration` |
| Output location | Public `Movies/RecorderX` file, needs `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | `MediaStore` insert, no storage permission needed |
| Thermal-aware bitrate throttling | **No-op** — `PowerManager` has no thermal-status API yet | Reactive: `PowerManager.addThermalStatusListener` (API 29+). Proactive forecast layer: `PowerManager.getThermalHeadroom` (API 30+ only — no-ops on API 29) |
| Foreground service type | Plain foreground service | Typed (`mediaProjection\|microphone`), permission-enforced from API 34 |
| Overlay window type | `TYPE_APPLICATION_OVERLAY` (introduced exactly at API 26 — no legacy branch needed) | same |

Every version-gated code path degrades to "the feature just doesn't run,"
never to a crash — e.g. `ThermalBitrateGovernor.start()` returns immediately
on API < 29, its proactive `getThermalHeadroom` polling additionally no-ops
on API 29 specifically (that call needs API 30), and `AudioMixEngine` simply
doesn't attempt `SystemAudioSource` below API 29, falling back to mic-only
if that's what Audio Source calls for.

## 9. APK size strategy

- **No Compose.** The runtime + tooling is the single biggest avoidable
  chunk for an app this size.
- **No image-loading, DI, reactive-stream, or unused layout libraries.** The
  entire dependency list is `core-ktx`, `appcompat`, and `material` — three
  artifacts, every one load-bearing.
- **Vector-only art.** The launcher icon and every in-app icon are hand-
  written `<vector>` XML (a few hundred bytes each) — zero PNG/raster
  mipmaps, zero density-bucket multiplication.
- **No bundled fonts.** Typography uses the system `sans-serif` family via
  `android:fontFamily`, which costs nothing.
- **`minifyEnabled` + `shrinkResources`** on the release build type, with
  `android.nonTransitiveRClass=true` so generated `R` classes don't carry
  the full transitive resource set.
- **`viewBinding` left off** — see the comment in `app/build.gradle.kts`;
  turning it on would generate an unused `Binding` class for zero benefit,
  since the one real layout (`overlay_recording_controls.xml`) is inflated
  directly and the main screen is built in Kotlin.

## 10. What's real vs. what's a starting point

Everything under `app/src/main/kotlin` is genuine, complete implementation —
correct `MediaCodec`/`MediaProjection`/`AudioRecord` API usage, not
pseudocode or stubs — and the project is structured so
`.github/workflows/android-build.yml` can actually compile it on GitHub's
runners (this environment has no Android SDK and no network access, so that
workflow is also this project's only real "does it build" check — see the
workflow's comments for how it provisions the SDK and Gradle Wrapper itself).

What genuinely benefits from time on a physical device, called out in code
comments at each spot:
- DSP constants in `DuckingProcessor`/`AudioMixEngine`/`ResidualBleedSuppressor`
  (attack/release times, the speech-detection RMS threshold, the soft-clip
  knee, the correlation gate and max suppression depth for residual-echo
  suppression) — reasonable starting values, worth ear-tuning against real
  content and real speaker/mic hardware.
- Whether `"max-fps-to-encoder"` and `KEY_OPERATING_RATE` are honored by your
  target chipsets' encoders (`VideoEncoderPipeline` -- both are best-effort
  hints layered on top of `ThermalBitrateGovernor`'s bitrate throttling,
  never load-bearing on their own).
- `ThermalBitrateGovernor`'s proactive-layer constants (`PROACTIVE_TRIGGER`,
  `PROACTIVE_FLOOR`, the poll interval) — `getThermalHeadroom`'s forecast
  accuracy is itself device-dependent per its own platform docs, so these are
  reasonable starting points, not values tuned against real thermal curves.
- The `DeviceTier` low/mid/high heuristic's exact RAM/core thresholds.

None of these are correctness bugs — they're calibration, which no amount of
static review substitutes for real hardware.
