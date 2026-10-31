# AI Subtitle Translator (Android)

Android version of the Windows app *Universal AI Movie Subtitle Translator*. It plays your
own video files and shows English subtitles generated **on the phone**: Whisper speech
recognition with its built-in translate task, a look-ahead buffer that stays ahead of the
playhead, a cache so a movie is never processed twice, and SRT export. No movie audio,
subtitles or keys leave the device. The only network use is downloading models in the
Model Manager.

Status: **Phase 1** (see [Phases](#phases)). Package `com.aivicventures.subtitletranslator`.

## The APK

`AI-Subtitle-Translator-universal.apk` in this folder is a universal build. It contains
arm64-v8a, armeabi-v7a, x86_64 and x86, is about 20 MB, and needs Android 8.0 (API 26) or
later. It is signed with the Android debug key, which is fine for sideloading and testing.
Use a real release key before any store upload.

Install it with `adb install -r AI-Subtitle-Translator-universal.apk`, or by copying the
file to the phone and opening it.

First run:
1. Open **Model Manager** and download a Whisper model and the Silero VAD (1 MB). Each
   download is checked against its pinned size and SHA-256 before it is installed.
   - Whisper base (60 MB) for mid-range phones.
   - Whisper small (190 MB) for better accuracy on fast phones.
2. Tap **Open a video…**, then press Play when the readiness gate says the buffer is ready.

## Using it

- **Look-ahead modes** (Settings):
  - Fast: 5 s ahead.
  - Balanced: 10 s ahead (default).
  - High Accuracy: 30, 45 or 60 s ahead, with temperature fallback.
  - Full Movie: processes everything first.
- **Readiness gate**: playback starts once the target amount of subtitles is ready. Press
  **Play now** to skip the wait.
- **Low buffer**: when the buffer runs low, the engine switches to catch-up mode (no
  fallback, shorter jobs). Optionally it also pauses playback until it has caught up
  (Settings).
- **Seeking**: a seek of more than 2 s aborts the current job and restarts at the new
  position. Parts already processed come from the cache instantly.
- **Cache**: subtitles are stored in SQLite, keyed by a fingerprint of the file and by
  model, task and language. Reopening a movie reuses everything already done, and the
  playback position is remembered.
- **Top bar**: −0.5 s and +0.5 s shift the subtitle timing; **SRT** exports the
  subtitles generated so far.
- **Source language**: "Detect automatically" is the default. A fixed language is used
  as given.

## Building

You need Android Studio's bundled JBR (Java 21), Android SDK 37, NDK 27.1.12297006 and
CMake 3.22.1.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`. The native code is
whisper.cpp v1.9.5, vendored **unmodified** in `third_party/whisper.cpp`; it is identical
to the release tarball, sha256 `ff1a9053…dc1720`.

## Architecture

| Part | Where | Notes |
|---|---|---|
| Native ASR | `app/src/main/cpp/` | whisper.cpp + ggml as shared libraries (details below this table). Small JNI layer: `initContext`, `detectSpeech`, `transcribe`, abort. |
| Audio | `audio/AudioWindowReader.kt`, `Resampler.kt` | Media3 `MediaExtractorCompat` demux + `MediaCodec` decode of one time window. Packets before the window are skipped without decoding. Polyphase windowed-sinc resampling to 16 kHz mono. |
| Recognition | `asr/WhisperAsr.kt`, `Hallucination.kt` | Pipeline below this table. |
| Look-ahead | `buffering/LookAheadEngine.kt`, `Coverage.kt` | Port of the Windows engine (details below this table). |
| Cache | `cache/CacheDb.kt` | Tables: movies, profiles, segments, coverage. |
| Subtitles | `subtitles/Layout.kt`, `Subtitles.kt` | Port of the Windows layout rules: 2 × 42 characters, ≤ 20 characters per second, ≤ 7 s per cue. SRT writer. |
| Models | `models/ModelManager.kt`, `assets/model_catalog.json` | Pinned Hugging Face commits, SHA-256 checked, resumable downloads. Default model chosen by device RAM. |
| UI | `ui/` | Main, Player (Media3 ExoPlayer + subtitle overlay), Model Manager, Settings, About & licences. |

Native ASR details:
- ggml CPU backends are loaded at runtime.
- On arm64 and x86_64, the best variant for the CPU is picked: dotprod, i8mm, SVE, AVX2 and so on.
- armeabi-v7a and x86 use a baseline build.

Recognition pipeline:
1. Silero VAD finds the speech in the window.
2. Speech regions are merged into groups (gap ≤ 1 s, ≤ 25 s each).
3. Each group is decoded separately. This gives exact timestamps and a language per group.
4. The movie's main language is locked once ≥ 3 confident groups agree with a ≥ 70 % share. This saves the language-detection encoder pass.
5. Unsure groups are re-checked in auto mode.
6. Filters run last: no-speech and log-prob gates, hallucination and sound-tag lists (ported from Windows), and a repeat-loop filter.

Look-ahead engine details:
- Coverage map.
- Owner-by-start rule and overlap dedup.
- Readiness gate.
- Catch-up mode.
- A generation counter, so a far seek aborts stale jobs.
- Background back-fill.
- Audio prefetch of the next window.

## Test results (honest)

All tests ran on the **Android emulator** (Medium Phone, API 36.1, x86_64, 6 vCPU with
AVX2, 4 GB RAM) on this PC. **No real phone was available**, so ARM performance, hardware
video decoding and thermal behaviour are untested.

| Test | Result |
|---|---|
| JVM unit tests (hallucination filter, sound tags, text clean-up, coverage map, layout, SRT, resampler, VAD grouping) | 12/12 pass |
| Build | Universal APK, 4 ABIs: arm64 has 7 CPU variants, x86_64 has 14 |
| Install, launch, Model Manager | Base, small and VAD downloaded in-app and SHA-256 verified |
| FLEURS test movie (4:17; es, ja, ko, fr, zh, ru, de, it, with the language changing every utterance), Full Movie, **base** | chrF **35.2**, 0/16 utterances missing, 0 lines outside speech, 1.83× real time |
| Same, **small** | chrF **46.5**, 0/16 missing, 0 outside speech, 0.55× real time |
| Same, base, before per-group decoding (one Whisper call per 30 s window) | chrF 24.1, 5/16 missing |
| Readiness gate, subtitle overlay | Gate passes after the first chunk; cues shown in sync, 2 lines, text shrinks to fit portrait width (screenshot checked) |
| Seek (time bar tap to 3:50 during playback) | Running job aborted, generation bumped, recognition restarted at the new playhead; first cue shown 17 s after the seek |
| Cache | Reopening the processed movie: 0 chunks re-processed, status complete immediately |
| SRT export | "Only 73 % ready, export?" prompt, system save dialog, valid SRT written to Downloads |
| Prefetch of the next audio window, on vs off (Korean film excerpt, Full Movie, base) | 1.88× vs 1.79× real time: kept on |

The Windows app scores chrF 45.8 on the same test movie, using small + M2M100.
`scripts/score_e2e.py` uses the same scoring rule as the Windows app's
`scripts/e2e_quality.py`.

### Korean feature film (local only)

The user's own Korean film was tested as a 6-minute excerpt (first 6 minutes, stream-copied,
1080p video with AAC audio). The film and every subtitle generated from it stayed on this
PC and the emulator; none are in this repository.

| Mode | Result |
|---|---|
| Full Movie, base | 360 s processed in 217 s (1.9× real time when the video is not playing). Main language locked to Korean after 3 groups. 60 lines, mostly coherent everyday dialogue, with weaker names and technical speech (base translate quality). 5 Whisper repeat-loops removed by the repeat filter. |
| Balanced, base, watching | Too slow on the emulator. Software decoding of the 1080p video leaves Whisper 0.25–0.5× real time, so the engine is in catch-up mode all the time. With aim-ahead catch-up, 11 jobs covered about 165 of 360 s and 13 cues were shown on time. Before this fix, 0 cues were shown on time. A real phone with hardware video decoding is needed to judge live mode. |

Found and fixed during these runs:
- Music before the first line was locked as "English". The lock now counts only groups that produced confident text.
- Whisper repeat-loops: one line repeated 7 times. A repeat filter now keeps only the first.
- Catch-up mode produced lines for moments that had already passed. It now starts where the playhead will be when the job finishes.
- Portrait subtitles wrapped into 4 ragged lines. The text now shrinks so each line fits.

### Not tested in Phase 1

- A real ARM phone: speed, battery, heat, and memory with small on a 4 GB phone.
- The SRT export save dialog end to end.
- Very long files (the 2 h film was only tested as a 6-minute excerpt).
- AC-3, E-AC-3 and DTS audio. These play only if the phone has a decoder; recognition
  reports "unsupported audio" otherwise.
- Multiple audio tracks: the first audio track is used.

## Known limitations

- **Translation quality** is Whisper's own translate task. It is weaker than the Windows
  app's M2M100 path for some languages. Whisper small is clearly better than base.
- **Speed on the emulator** is not representative. Video is decoded in software, which
  takes CPU from Whisper, and the audio decoder pays a binder round-trip per packet.
  In Balanced mode with base and the video playing, the emulator could not keep up, and
  catch-up mode left gaps. A real phone's hardware video decoder and NEON/dotprod kernels
  should do better, but this has not been measured.
- Telugu and Tamil are reported as not supported, by the publisher's decision.

## Phases

1. **Phase 1 (this build)**:
   - Playback, on-device Whisper translate to English, look-ahead buffering, cache, SRT
     export, Model Manager, and licences screen.
2. **Phase 2**:
   - M2M100 418M (ONNX Runtime, MIT) for text translation after Whisper transcription.
   - Per-language quality comparison against the Whisper translate task.
3. **Phase 3**:
   - Audio track picker.
   - Optional LGPL-only FFmpeg audio decoder for AC-3/DTS, with a licence check.
   - Real-device tuning: threads, big-core pinning, and a battery mode.

## Privacy and licences

- No analytics, no accounts, no backups (`allowBackup=false`). The only permission is
  INTERNET, used for model downloads.
- If a cloud feature is ever added, it will be off by default, and its key will be stored
  with Android Keystore.
- Every dependency is MIT or Apache-2.0, with **no GPL or non-free code**. See
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
  [docs/dependencies-release.txt](docs/dependencies-release.txt). The licence texts are
  also shown in the app.
- Test media (`test-media/`) is gitignored and stays on this PC.
