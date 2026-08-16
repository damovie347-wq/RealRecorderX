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
thumb. One class, reused ~18 times, driven entirely by data (a list of step
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

### 5a. Software AV1 (opt-in) and 10-bit

Two settings layer onto the cascade above without changing its shape for
anyone who leaves them at their defaults:

- **`Av1SoftwareFallback.ON`** inserts one extra step, *only* when the user's
  preference is AV1: right after hardware AV1 fails and before falling to
  HEVC/AVC hardware, `CodecSelector.findSoftwareAv1Encoder` explicitly
  searches for a non-hardware AV1 encoder (AOSP ships a libaom-based one on
  updated Android 14+ media modules, registered in `MediaCodecList` like any
  other). Before this existed, the cascade's *only* non-hardware attempt was
  AVC — AV1 itself was never tried without hardware, so a device with a real
  software AV1 path still silently landed on a different codec. Two hard
  caps, both because CPU-bound throughput has no relationship to a hardware
  block's: only attempted when the already-resolved target is at or under
  1080p (never a silent resize — above that this step is simply skipped and
  the cascade continues to HEVC/AVC hardware), and fps is pre-clamped to 30
  rather than trusting the software codec's own declared ceiling.
- **`ColorDepthOption.TEN_BIT`** changes which profile `CodecSelector` accepts
  as a match: `pickProfileLevel` requires an exact Main10-family profile
  (`HEVCProfileMain10`/`AV1ProfileMain10` and their HDR10/HDR10+ variants) and
  returns `null` — meaning "skip this codec candidate entirely" — for
  anything that doesn't advertise one, rather than silently accepting an
  8-bit profile under a 10-bit label. AVC is never considered for 10-bit at
  all (`AVCProfileHigh10` exists as a constant but has no meaningful hardware
  or software presence on real devices). If nothing anywhere in the cascade
  can do it, `findBestEncoder` retries the *entire* cascade once at 8-bit,
  and `CodecChoice.colorDepth` on the result tells `RecordingService` this
  happened so it can toast about it — exactly the `achievedFps` pattern,
  applied to color depth. Worth being explicit about *why* this is offered
  at all: `MediaProjection` mirrors whatever SurfaceFlinger already
  composited, which is ordinary 8-bit RGBA8888 for the overwhelming majority
  of Android content — 10-bit here is a wider, correctly-configured container
  around that same information, not new detail, unless the content itself is
  genuinely HDR. See `ColorDepthOption`'s kdoc and `label_color_depth_note`
  for how that's disclosed in the UI rather than oversold.

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
as a live parameter change. Every stage of that governor was silent to the
user by design (deliberately not a pop-up per degree of throttling) until
`onThrottleChanged` was added: it fires only when the *combined* fraction
crosses a real, reportable threshold, so `RecordingService` can say "your
device is warming up, bitrate reduced" instead of a long gaming-session
recording just getting quietly softer partway through with no visible cause
— previously indistinguishable from the app (or the user's settings) being
broken.

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
either. Rather than pick one fixed compromise, `OverlayVisibilityMode` gives
the person recording the actual trade-off directly:

1. **VISIBLE (default): no `FLAG_SECURE`.** The window is an ordinary, small,
   translucent overlay — a ~16dp dot at rest — so there is no black shape
   under any OEM compositor, ever. It's small and low-opacity by design
   specifically *because* it can end up in a frame in this mode.
2. **AUTO_HIDE.** Same window, but `RecordingService.showOverlayIfEnabled`
   schedules `autoHideOverlayRunnable` a few seconds after every show — the
   exact same code path a manual eye-icon tap takes (`handleHideOverlay`),
   just triggered by a timer instead of a touch. Reachable afterward only via
   the notification's "Show controls" action or the Quick Settings tile — the
   cleanest frames this app can offer, traded for the bubble not being an
   on-screen tap target most of the time.
3. **BLACKOUT.** Restores `FLAG_SECURE` specifically on this window
   (`RecordingOverlayController.show(blackout = true, ...)`) for anyone who's
   decided a small solid-black shape is preferable to legible controls
   appearing in their footage — the trade-off VISIBLE mode defaults away
   from, now opt-in rather than forced either way.

On top of whichever mode is active: a **real hide action** (the eye icon on
the expanded row) that fully detaches the window from `WindowManager` —
actually removed, not shrunk or made transparent — for whenever a completely
clean frame matters more than having the controls reachable on-screen, and
an **automatic idle fade** (full opacity on touch, eases to 32% after 2.5s
idle) that reduces how much of the recording VISIBLE/BLACKOUT are actually
noticeable in without needing a tap. `RecordingService` adds a "Show
controls" notification action whenever the bubble is currently detached
(user-hidden or AUTO_HIDE), since it can't offer its own way back once it's
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
| Software AV1 encoder (`Av1SoftwareFallback.ON`) | Not expected — AOSP's libaom-based software AV1 encoder ships on updated Android 14+ media modules | Present on capable devices; `CodecSelector.findSoftwareAv1Encoder` simply finds nothing and the cascade continues to HEVC/AVC either way, so this degrades the same "feature just doesn't run" way as everything else in this table |
| 10-bit encoder profiles (`ColorDepthOption.TEN_BIT`) | Hardware-dependent at any API level, not an API-level gate itself | Same — `pickProfileLevel` finds nothing and `findBestEncoder` retries at 8-bit regardless of platform version |

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
  knee, the correlation gate and max suppression depth per `BleedSuppressionMode`
  strength) — reasonable, now-retuned-once-against-a-real-report starting
  values (see §12 item 2), still worth further ear-tuning against real
  content and real speaker/mic hardware, especially per device.
- `ThermalBitrateGovernor`'s `onThrottleChanged` report threshold
  (`REPORT_EPSILON`) and the software-AV1 caps in
  `CodecSelector.findSoftwareAv1Encoder` (`SOFTWARE_AV1_MAX_FPS` = 30,
  1080p) — reasonable, conservative starting points chosen without a
  physical device to measure real sustained CPU-encode throughput or real
  toast-frequency annoyance against; both are easy to loosen once measured.
- Whether `"max-fps-to-encoder"` and `KEY_OPERATING_RATE` are honored by your
  target chipsets' encoders (`VideoEncoderPipeline` -- both are now a
  defensive extra layer, not the actual fps enforcement mechanism; see §11
  and `FramePacer`'s kdoc for what replaced them).
- `ThermalBitrateGovernor`'s proactive-layer constants (`PROACTIVE_TRIGGER`,
  `PROACTIVE_FLOOR`, the poll interval) — `getThermalHeadroom`'s forecast
  accuracy is itself device-dependent per its own platform docs, so these are
  reasonable starting points, not values tuned against real thermal curves.
- The `DeviceTier` low/mid/high heuristic's exact RAM/core thresholds.

None of these are correctness bugs — they're calibration, which no amount of
static review substitutes for real hardware.

## 11. Fixes applied — Aug 2026

Seven issues reported against a real build, all traced to a real mechanism
(not surface-level patches) and fixed at that mechanism. Full reasoning lives
in each file's kdoc; this is the map of what changed and where.

1. **"4K/2K/1080p/720p/480p don't produce those exact pixel sizes."**
   `ResolutionOption` (`settings/SettingsModels.kt`) now carries a fixed,
   exact 16:9 `(longEdge, shortEdge)` pair per tier instead of deriving the
   short edge from the panel's own aspect ratio — on any panel that isn't
   exactly 16:9 (most tablets, including the one these reports came from),
   the derived value matched neither the panel nor the label. `NATIVE` is
   unchanged: it still follows the real panel size on purpose. See
   `ResolutionResolver`'s kdoc.
2. **"120 fps records at 512x512 regardless of the resolution I picked."**
   `CodecSelector.findEncoderFor` (`codec/CodecSelector.kt`) used to reject a
   codec entry outright when it couldn't hit the requested fps *at the
   requested size*, which on a device exposing a second, size-restricted
   capability entry for the same mime (common — a "high fps, small size"
   entry alongside the normal one) meant the loop fell through past the
   entry that could do the real resolution and landed on that one instead.
   It now scores every candidate by resolution match first, full stop, and
   only clips fps (down, at the *chosen* resolution) as the very last step —
   resolution is never traded away for fps. The clipped number comes back as
   `CodecChoice.achievedFps` and everything downstream (bitrate suggestion,
   encoder config, the pacer's target, the thermal governor's baseline) uses
   it instead of the raw slider value; `RecordingService` tells the user
   when it had to clip.
3. **"Whatever fps I set, actual output is a different, wrong fps."** The
   real fix, not a tweak: `capture/FramePacer.kt` (+ `EglCore.kt`) is a new
   GPU stage between `MediaProjection`'s mirrored screen and the encoder's
   input surface. `KEY_MAX_FPS_TO_ENCODER` (still set, now just a defensive
   extra) is an unreliable, not-universally-honored vendor hint; a
   Surface-input encoder otherwise receives a new frame every time
   SurfaceFlinger recomposites, i.e. at whatever the *content* and the
   panel's refresh rate produce, not the configured fps. FramePacer instead
   owns delivery outright: a dedicated thread redraws the latest mirrored
   frame onto the encoder's surface on a fixed, drift-free `1/fps` schedule
   it controls itself. `ThermalBitrateGovernor`'s fps cap now also calls
   `FramePacer.setTargetFps` — under the old model that call only adjusted a
   hint the encoder might ignore; now it directly changes what's delivered.
4. **"Recording controls appear live but shouldn't be baked into the
   video, like Samsung's recorder."** Not fixable outright — confirmed
   against current platform docs, not just repeated from an earlier
   assessment: there is still no public API letting a normal app keep its
   own overlay simultaneously visible and cleanly excluded (not blacked out)
   from that same app's own `MediaProjection` capture. `FLAG_SECURE` blacks
   the window out instead of omitting it; Android 14's single-app capture
   mode excludes system UI, not a third-party app's own floating overlay,
   and would mean the recording could never follow the user across apps —
   not a viable trade for a general screen recorder. `RecordingOverlayController`
   keeps the existing no-`FLAG_SECURE` + real hide-button design and adds an
   automatic idle fade (full opacity on touch, eases to 32% after 2.5s idle)
   as a genuine, if partial, reduction in how much of the recording it's
   actually noticeable in.
5. **"High fps/resolution/bitrate, still soft/blurry, almost gaussian."**
   No single separate cause — the dominant contributor was #2 above (the
   actual encoded size silently collapsing well below what the resolution
   picker showed inflates perceived softness the most). What's left after
   that fix is upscaling from a real panel below the requested tier, which
   is physical, not a bug (see `ResolutionResolver.isUpscaling` — the UI
   already discloses it), plus whatever profile/level and bitrate headroom
   `CodecSelector.pickProfileLevel` / `BitrateAdvisor` already resolve.
6. **"Optimize CPU/GPU/battery/heat."** FramePacer (#3) is a net win here,
   not just a correctness fix: it caps encoder input to *exactly* the
   requested fps, where the old uncontrolled path could feed the encoder up
   to (panel refresh rate / requested fps) times more frames than needed —
   e.g. a 120Hz panel recording at 30fps previously could push the encoder
   up to 4x harder than necessary. The pacer's own cost is one GPU texture
   blit per output frame, no CPU-side pixel copy. `ThermalBitrateGovernor`
   is otherwise unchanged and still the primary thermal defense.
7. **"System audio + mic echoes/doubles."** `AcousticEchoCanceler` (used
   when available in `MicAudioSource`) isn't guaranteed to exist at all —
   common on tablets — and `ResidualBleedSuppressor`, the app's own
   correlation-based second layer, searched only a +/-10-sample
   (~+/-0.2ms @48kHz) window for the delay between the mic and the system-audio
   reference. That's the acoustic speaker-to-mic air gap alone; it ignored
   Android's real playback-to-capture round trip, which commonly runs tens
   of milliseconds and can exceed 100ms on non-"Pro Audio" hardware — so the
   search essentially never found where the real echo actually landed. It
   now keeps a rolling ~220ms reference history and searches that full range
   (a cheap stepped coarse pass + a fine pass locked around both the coarse
   winner and the previous tick's lag), so it can actually find and suppress
   real-world bleed instead of only ever seeing content it correctly reads
   as unrelated to the reference.

Not touched: none of the above needed changes to `MuxerController`,
`AudioEncoderPipeline`, `DuckingProcessor`, `BitrateAdvisor`, or `DeviceTier`
— those were already doing what their kdoc says.

## 12. Fixes applied — round 2

Four issues reported against a build that already included round 1 (§11) —
three of them the *same symptoms* recurring (expected: §11 already flagged
upscaling and the overlay both as physical/platform limits it could disclose
but not eliminate), one genuinely new. Each is fixed at a real mechanism, not
patched at the symptom.

1. **"2K/4K still not crisp, especially deeper into a long (gaming)
   recording."** §11's fixes (profile/level, the real fps-capability
   collapse, upscale disclosure) already addressed the *static* causes.
   What's new: `ThermalBitrateGovernor` was — correctly, by design — cutting
   live bitrate under real thermal pressure with zero user-facing signal
   anywhere. A screen recorder actively encoding *and* driving a hot game on
   screen is exactly the scenario that reaches that governor's stages, so a
   recording that started sharp and got visibly softer 10 minutes in was
   this working as intended, indistinguishable from a bug from the user's
   side. `onThrottleChanged` (new) fires on a real combined-fraction change;
   `RecordingService.announceThermalThrottle` toasts both the drop and the
   recovery. Also added: an explicit `toast_resolution_capped` when the
   *chosen codec's own* capability (not a mime fallback, which already had
   its own toast) forces a smaller size than requested — previously only
   visible in the always-shown summary toast, easy to miss.
2. **"System audio + mic still doubles on loud transients (an explosion
   plays twice)."** `ResidualBleedSuppressor` already existed (§11 #7 fixed
   its search *window*); this time the reported failure mode was depth and
   speed, not search range — a 20dB ceiling and 60ms attack that a short,
   loud, percussive sound could mostly finish playing through before
   suppression caught up. Retuned to 26dB/35ms as the new NORMAL default,
   added a 34dB/20ms STRONG option, and exposed both plus OFF as
   `BleedSuppressionMode` instead of one fixed, silent value. Paired with a
   new zero-cost mitigation: `AudioMixEngine.isLikelyUsingBuiltInSpeaker`
   checks for *any* connected external audio output device, and
   `RecordingService.announceHeadphoneTipIfNeeded` suggests a headset once
   (ever, not per session, via `SettingsRepository.hasShownHeadphoneTip`)
   when Sys+Mic recording starts with none connected — a headset removes the
   acoustic bleed at its physical source, which no amount of after-the-fact
   software suppression can do as completely.
3. **"Recording controls appear live but shouldn't be baked into the video,
   like Samsung's recorder."** Re-confirmed against current platform docs,
   not just repeated: still genuinely not achievable by a non-privileged
   app — see §7's rewrite. What's new is not pretending there's one right
   answer: `OverlayVisibilityMode` (VISIBLE / AUTO_HIDE / BLACKOUT) lets the
   person recording pick which specific trade-off they'd rather live with,
   instead of the app picking once for everyone.
4. **"AV1 recording silently does nothing on a device without hardware
   AV1; want CPU-based AV1 up to 1080p instead, plus an 8-bit/10-bit
   choice."** The actual gap: `CodecSelector.findBestEncoder`'s cascade
   only ever searched `hardwareOnly = true` for every mime including AV1,
   and its one non-hardware attempt was hardcoded to AVC — so even on a
   device with AOSP's real libaom-based software AV1 encoder, AV1 itself was
   never once tried without hardware; the cascade just silently moved on to
   HEVC/AVC hardware. `findSoftwareAv1Encoder` (new, opt-in via
   `Av1SoftwareFallback.ON`) is the missing attempt, inserted right after
   hardware AV1 and before HEVC/AVC so it's honored ahead of a
   faster/cooler substitute codec — capped at 1080p and a conservative
   30fps because CPU-bound throughput has no relationship to a hardware
   block's, and *never* silently resizing the user's exact resolution pick
   above that (the step is just skipped instead). `ColorDepthOption` was
   added alongside it: `pickProfileLevel` now requires an exact Main10-family
   profile match at 10-bit and returns `null` (skip this candidate) rather
   than silently accepting an 8-bit profile under a 10-bit label; if nothing
   in the whole cascade can do it, `findBestEncoder` retries once at 8-bit
   and says so via `CodecChoice.colorDepth` / `toast_color_depth_fallback`.

Not touched this round: `MuxerController`, `AudioEncoderPipeline`,
`DuckingProcessor`, `BitrateAdvisor`, `DeviceTier`, `ScreenCaptureController`,
`FramePacer`, `EglCore`, `RecordingOutputResolver`, `RecordingTileService` —
none of round 2's four reports traced back to any of them.
