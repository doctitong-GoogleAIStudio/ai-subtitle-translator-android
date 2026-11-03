# Live Floating Subtitles: inspection and implementation plan

Git checkpoint before any change: tag `pre-live-subtitles` (commit `89839f2`).

## What the existing app is (Phase 1 of the brief)

| Question | Answer |
|---|---|
| Language / framework | Native Android, Kotlin, views built in code (`ui/Views.kt`), Material Components 1.14. No React Native, Flutter or web code. |
| Build | AGP 9.4.1, compileSdk 37, targetSdk 36, minSdk 26, NDK 27 (CMake). One universal APK, release-signed with the existing key (`keystore.properties`). |
| Speech recognition | whisper.cpp v1.9.5 through JNI (`asr/WhisperNative.kt`, `asr/WhisperAsr.kt`): Silero VAD, per-group language detection with a language lock, hallucination and repeat filters (`asr/Hallucination.kt`). Works on any PCM buffer, so it can take live audio. |
| Translation | M2M100 418M int8 on CTranslate2 (`translate/M2M100Translator.kt`, 64-bit phones), otherwise Whisper's own translate task. Same "Automatic" rule as the player (`ModelSpec.autoMt`). |
| Subtitle layout | `subtitles/Layout.kt`: line wrapping (CJK-aware), two-line balancing, splitting long text for display. |
| Device tuning | `device/CpuTopology.kt` (fast-core pinning), `device/PowerMonitor.kt` (battery saver, heat). |
| Uploaded movies | `PlayerActivity` + `LookAheadEngine`: audio decoded from the file with `AudioWindowReader`, subtitles cached in SQLite. |
| AI APIs / credentials | **None.** Everything is on-device; the app has no INTERNET permission and no API keys. |
| Accounts, subscriptions, billing, backend | **None.** There is no Laravel or other backend for this app. |

## Decisions

1. **Same on-device engine for live audio.** Whisper + M2M100 already run on the phone, so live
   subtitles need no server, no API key and no network permission. Audio and dialogue never
   leave the phone. A text-generation API would not give speech recognition anyway.
2. **No cloud provider in this build.** The translation step sits behind a small interface
   (`live/LiveTranslation.kt`), so a cloud provider can be added later. It would need a
   backend to hold its key: the brief forbids keys in the app, and this app has no backend.
   Usage metering is built in. The cost estimate is 0 because nothing is billed.
3. **Audio sources:**
   - Internal: `AudioPlaybackCapture` with MediaProjection consent.
   - Microphone: `AudioRecord` with the `VOICE_RECOGNITION` source.
   - Second device: the microphone, with big subtitles in the app instead of the overlay.

   Nothing bypasses capture policy or DRM. If an app blocks capture, the app detects it and
   says so (media is playing but only digital silence arrives). It offers the microphone and
   switches only after the user taps.
4. **Overlay:**
   - `TYPE_APPLICATION_OVERLAY` windows in a foreground service: a subtitle window and a
     small control bubble.
   - The subtitle window is touch-through. On Android 12+ it is drawn at most at the system's
     maximum obscuring opacity (0.8), because otherwise Android blocks touches to the video
     app underneath.
   - Touches are taken only while the user moves the subtitles.
5. **Streaming:** utterance segmentation (energy VAD with an adaptive noise floor, end of
   speech on silence, forced cuts at the quietest point). Each utterance is decoded by
   `WhisperAsr`, whose Silero VAD and filters stay in use. Fast mode also shows provisional
   subtitles of the utterance in progress, replaced by the final text.
6. **No new signing key.** The release build uses the existing key.

## Modules

| New file | Role | Reuses |
|---|---|---|
| `live/LiveConfig.kt` | audio modes, Fast / High Accuracy tuning, appearance | — |
| `settings/LiveSettings.kt` | saved live settings (own preferences file) | `Settings` pattern |
| `live/UtteranceSegmenter.kt` | VAD + endpointing, partial snapshots, buffer limits | — |
| `live/SubtitleTimeline.kt` | sync queue: delay, expiry, stale-line drop, provisional replace, repeat merge, line limit | `Layout`, `Hallucination.norm` |
| `live/ContextMemory.kt` | sliding dialogue context for Whisper's prompt, name glossary and consistent spelling | — |
| `live/LiveStats.kt` | per-stage latency, usage metering | — |
| `live/AudioSources.kt` | playback capture, microphone, test file | `Resampler`, `AudioWindowReader` |
| `live/LiveEngine.kt` | capture → segmenter → Whisper → M2M100 → timeline; blocked-capture detection | `WhisperAsr`, `M2M100Translator`, `ModelManager`, `CpuTopology`, `PowerMonitor` |
| `live/LiveSession.kt` | state shared by the service and the screens | — |
| `live/LiveSubtitleService.kt` | foreground service (mediaProjection / microphone), notification, lifecycle | — |
| `live/SubtitleOverlay.kt`, `live/OutlinedTextView.kt` | floating subtitle window and control bubble | — |
| `ui/LiveActivity.kt` | Live dashboard: options, Start / Settings / Stop, status, errors, usage | `Views.kt` |
| `ui/LiveSettingsActivity.kt` | subtitle, translation, audio and performance settings | `Views.kt` |
| `ui/SecondDeviceActivity.kt` | large subtitles on the phone for a TV or another device | — |

Changed: `WhisperAsr.run` gets optional `prompt`, `timeOffset` and `provisional` parameters.
The defaults keep the movie player's behaviour. `MainActivity` gets the Live card. The
manifest gets the overlay, record-audio, notification and foreground-service permissions,
and still no INTERNET.

## Order of work

1. Pure-Kotlin core (segmenter, timeline, context, stats) with unit tests.
2. Audio sources and the engine; `WhisperAsr` additions.
3. Service, overlay, notification.
4. Live dashboard, settings screen, second-device screen, home-screen card.
5. Build, lint, unit tests. Emulator test with a FLEURS test movie through the test file
   source, then real playback capture and the permission-denied paths.
6. Release APK with the existing key, AAB, docs (compatibility report, permission disclosure,
   release notes), commit and push.
