# AI Subtitle Translator (Android)

Android version of the Windows app *Universal AI Movie Subtitle Translator*. It plays your
own video files and shows English subtitles generated **on the phone**: Whisper speech
recognition, translation to English (M2M100 418M or Whisper's own translate task, whichever
measured better for the chosen speech model), a look-ahead buffer that stays ahead of the
playhead, a cache so a movie is never processed twice, and SRT export. No movie audio,
subtitles or keys leave the device. The only network use is downloading models in the
Model Manager.

Status: **Phase 2** (see [Phases](#phases)). Package `com.aivicventures.subtitletranslator`.

## The APK

`AI-Subtitle-Translator-universal.apk` in this folder is a universal build. It contains
arm64-v8a, armeabi-v7a, x86_64 and x86, is about 22.5 MB, and needs Android 8.0 (API 26) or
later. It is signed with the Android debug key, which is fine for sideloading and testing.
Use a real release key before any store upload.

Install it with `adb install -r AI-Subtitle-Translator-universal.apk`, or by copying the
file to the phone and opening it.

First run:
1. Open **Model Manager** and download a Whisper model and the Silero VAD (1 MB). Each
   download is checked against its pinned size and SHA-256 before it is installed.
   - Whisper base (60 MB) for mid-range phones.
   - Whisper small (190 MB) for better accuracy on fast phones.
   - Optional: M2M100 418M translator (496 MB, 64-bit phones only). It makes translations
     from Whisper tiny and base much better (see [Test results](#test-results-honest)).
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
- **Translation** (Settings):
  - Automatic (default): M2M100 with Whisper tiny and base, Whisper's own translation
    with small. This follows the FLEURS results below.
  - M2M100 always.
  - Whisper only: no extra model, fastest.
  - Without the M2M100 download, or on a 32-bit phone, Whisper translates.
  - Each choice has its own cache, so lines from the two translators are never mixed.

## Building

You need Android Studio's bundled JBR (Java 21), Android SDK 37, NDK 27.1.12297006 and
CMake 3.22.1.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`. The native code is vendored
**unmodified**:
- whisper.cpp v1.9.5 in `third_party/whisper.cpp`, identical to the release tarball,
  sha256 `ff1a9053…dc1720`.
- CTranslate2 v4.8.3 in `third_party/ctranslate2`, with only the parts the build needs:
  spdlog, cpu_features, Ruy and cpuinfo at the submodule commits of that release. Tarball
  sha256s: CTranslate2 `255009e3…`, spdlog `4530d53b…`, cpu_features `9b2019da…`,
  Ruy `3c2181c9…`, cpuinfo `4379348e…`.

Android-specific build settings live in `app/src/main/cpp/CMakeLists.txt`, not in the vendored
code. CTranslate2 is built with Ruy as its only matrix library (no MKL, no OpenMP), and only for
arm64-v8a and x86_64. Bionic lacks `pthread_setaffinity_np`; `ct2_android_compat.h` supplies
it and is force-included.

## Architecture

| Part | Where | Notes |
|---|---|---|
| Native ASR | `app/src/main/cpp/` | whisper.cpp + ggml as shared libraries (details below this table). Small JNI layer: `initContext`, `detectSpeech`, `transcribe`, abort. |
| Audio | `audio/AudioWindowReader.kt`, `Resampler.kt` | Media3 `MediaExtractorCompat` demux + `MediaCodec` decode of one time window. Packets before the window are skipped without decoding. Polyphase windowed-sinc resampling to 16 kHz mono. |
| Recognition | `asr/WhisperAsr.kt`, `Hallucination.kt` | Pipeline below this table. |
| Translation | `translate/M2M100Translator.kt`, `SentencePieceBpe.kt`, `cpp/subtrans_mt.cpp` | Details below this table. |
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

Translation details:
- When M2M100 is used, Whisper **transcribes** each group in its own language. M2M100 then
  translates the lines, one batch per language.
- Languages M2M100 does not cover fall back to Whisper's translate task for that group.
- The tokenizer is a small Kotlin SentencePiece BPE reader. A unit test checks it against
  sentencepiece 0.2.2's output on 18 samples: 12 languages in 8 writing systems, plus full-width text, half-width katakana, ligatures, tabs, newlines and emoji.
- CTranslate2 runs the int8 model on the CPU.
- Decoding uses beam 4 in Full Movie mode and greedy decoding otherwise. On FLEURS, beam 4
  scored the same as greedy (42.3) and cost about 1.5× the translation time.
- Decoding options match the Windows app: repetition penalty 1.1, no repeated 4-grams.

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
| JVM unit tests (hallucination filter, sound tags, text clean-up, coverage map, layout, SRT, resampler, VAD grouping; Phase 2: SentencePiece normalisation and reference tokenisation, M2M100 language codes) | 15/15 pass |
| Build | Universal APK, 4 ABIs: arm64 has 7 CPU variants, x86_64 has 14; CTranslate2 on arm64 and x86_64 |
| Install, launch, Model Manager | Base, small and VAD downloaded in-app and SHA-256 verified. Phase 2: the 4-file M2M100 model (496 MB) downloaded in-app, every file SHA-256 verified |
| M2M100 load on the emulator | 1.7–5.2 s; CTranslate2 selected AVX2 kernels with Ruy for int8 |
| FLEURS test movie (4:17; es, ja, ko, fr, zh, ru, de, it, with the language changing every utterance), Full Movie, **base** | chrF **35.2**, 0/16 utterances missing, 0 lines outside speech, 1.83× real time |
| Same, **small** | chrF **46.5**, 0/16 missing, 0 outside speech, 0.55× real time |
| Same, base, before per-group decoding (one Whisper call per 30 s window) | chrF 24.1, 5/16 missing |
| Readiness gate, subtitle overlay | Gate passes after the first chunk; cues shown in sync, 2 lines, text shrinks to fit portrait width (screenshot checked) |
| Seek (time bar tap to 3:50 during playback) | Running job aborted, generation bumped, recognition restarted at the new playhead; first cue shown 17 s after the seek |
| Cache | Reopening the processed movie: 0 chunks re-processed, status complete immediately |
| SRT export | "Only 73 % ready, export?" prompt, system save dialog, valid SRT written to Downloads |
| Prefetch of the next audio window, on vs off (Korean film excerpt, Full Movie, base) | 1.88× vs 1.79× real time: kept on |

The FLEURS rows above are from Phase 1 (Whisper translate only). The Windows app scores
chrF 45.8 on the same test movie, using small + M2M100.

### Phase 2: M2M100 vs Whisper translate, per language

Two FLEURS test movies were used, each in Full Movie mode on the emulator:
- Movie 1 is the Phase 1 movie.
- Movie 2 holds utterances 3 and 4 of each language. It was built with the same layout from
  the FLEURS clips the Windows app already has.

Together they give **32 utterances, 4 per language**. Every configuration was run with the same
APK, and the scores are chrF with the same rule as above.

| Speech model + translation | Movie 1 | Movie 2 | **Both** | Time for movie 1 (4:17) | Peak memory (PSS) |
|---|---|---|---|---|---|
| base + Whisper translate | 35.4 | 30.6 | **33.0** | 210 s | 0.25 GB |
| base + M2M100 (beam 4) | 42.3 | 39.9 | **41.1** | 330 s | 0.93 GB |
| base + M2M100 (greedy) | 42.3 | – | – | 274 s | – |
| small + Whisper translate | 46.7 | 42.8 | **44.8** | 576 s | 0.49 GB |
| small + M2M100 (beam 4) | 43.9 | 41.2 | **42.6** | 678 s | 1.10 GB |

Per language, both movies pooled (4 utterances each):

| Language | base + Whisper | base + M2M100 | small + Whisper | small + M2M100 |
|---|---|---|---|---|
| German | 26.7 | **41.9** | **50.4** | 41.3 |
| Spanish | 41.8 | **45.3** | 42.9 | 43.0 |
| French | 37.0 | **42.2** | **47.0** | 41.1 |
| Italian | **45.6** | 43.0 | 49.4 | **51.7** |
| Japanese | 19.0 | **31.1** | 29.1 | **33.4** |
| Korean | 25.8 | **37.4** | **50.7** | 44.3 |
| Russian | 41.2 | **50.9** | **55.2** | 52.6 |
| Chinese | 26.0 | **38.9** | **38.2** | 36.8 |

What this shows:
- **With base**, M2M100 is clearly better: +8.1 chrF overall, and better in 7 of 8
  languages. Base's own translate task is weak (garbled names, missing clauses), while its
  transcription is good enough for M2M100.
- **With small**, Whisper's own translation is better overall (+2.2) and clearly better for
  German, French and Korean. M2M100 is better for Japanese and Italian.
- Four utterances per language is a small sample: a single sentence can move a language's
  score by several points. The overall direction is the same on both movies, though.
- The default therefore follows the speech model:
  - **Automatic** uses M2M100 with tiny and base, and Whisper's own translation with small.
  - Users can still choose M2M100 or Whisper for every model.
  - A per-language choice was not added, because 4 utterances per language are too few to
    support it.
- Cost on the emulator:
  - Translation takes 7–19 s per 30 s chunk (about 2–3 s per line), and the model adds about
    0.6 GB of memory.
  - CTranslate2 on x86 Android uses Ruy rather than oneDNN/MKL as on the PC, where the
    same model needs 0.2–0.3 s per line.
  - ARM phones use Ruy's NEON kernels, its main target, but **no real phone has been
    measured**.
- One German line in movie 2 starts 1.5 s before the speech and is counted as "outside" by
  the fixed scoring rule in every configuration, so it costs all configurations equally.
- One run was redone. In the first attempt at base + Whisper translate on movie 2, the
  emulator's log buffer overflowed and lost the first three chunks; it was rerun with a
  16 MB buffer.
- Reproduce with:
  `python scripts/score_e2e.py test-media/fleurs_e2e_truth.json run1.log test-media/fleurs_e2e2_truth.json run2.log`.
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
| Full Movie, base + M2M100 (Phase 2, Automatic) | 360 s processed in 346 s (1.04× real time), against 217 s with Whisper translate. 71 lines (Phase 1: 60), 7.1 words per line on average (Phase 1: 9.2), all in English, no word loops, 1 duplicate line. Peak memory 0.97 GB. M2M100 took 5–19 s per 30 s chunk. To keep the film's subtitles on this PC, only these counts were taken; the lines themselves were not reviewed here. |

Found and fixed during these runs:
- Music before the first line was locked as "English". The lock now counts only groups that produced confident text.
- Whisper repeat-loops: one line repeated 7 times. A repeat filter now keeps only the first.
- Catch-up mode produced lines for moments that had already passed. It now starts where the playhead will be when the job finishes.
- Portrait subtitles wrapped into 4 ragged lines. The text now shrinks so each line fits.

### Not tested yet

- A real ARM phone: speed, battery, heat, and memory with small on a 4 GB phone. M2M100
  speed and memory on ARM (Phase 2) are also unmeasured.
- The SRT export save dialog end to end.
- Very long files (the 2 h film was only tested as a 6-minute excerpt).
- AC-3, E-AC-3 and DTS audio. These play only if the phone has a decoder; recognition
  reports "unsupported audio" otherwise.
- Multiple audio tracks: the first audio track is used.

## Known limitations

- **Translation quality**: small with its own translation was best on FLEURS (44.8). base
  with M2M100 (41.1) is the best choice for phones too slow for small.
- **M2M100 needs a 64-bit phone**. CTranslate2's CPU dispatch does not build for 32-bit ARM
  or x86, so 32-bit phones always use Whisper's translation.
- **M2M100 needs memory**: about 0.6 GB on top of Whisper, so with small the app peaked at
  1.1 GB on the emulator. That is why Automatic does not pair small with M2M100.
- **Speed on the emulator** is not representative. Video is decoded in software, which
  takes CPU from Whisper, and the audio decoder pays a binder round-trip per packet.
  In Balanced mode with base and the video playing, the emulator could not keep up, and
  catch-up mode left gaps. A real phone's hardware video decoder and NEON/dotprod kernels
  should do better, but this has not been measured.
- Telugu and Tamil are reported as not supported, by the publisher's decision.

## Phases

1. **Phase 1** (done):
   - Playback, on-device Whisper translate to English, look-ahead buffering, cache, SRT
     export, Model Manager, and licences screen.
2. **Phase 2 (this build)**:
   - M2M100 418M for text translation after Whisper transcription.
   - It runs on CTranslate2 (MIT) with an int8 model. ONNX Runtime was planned, but
     measurements on the PC showed it needs 2–4× the memory, and the int8 export was about
     10× slower per token; see THIRD_PARTY_NOTICES.md.
   - Per-language quality comparison against the Whisper translate task (above).
   - Automatic translator choice per speech model.
3. **Phase 3**:
   - Audio track picker.
   - Optional LGPL-only FFmpeg audio decoder for AC-3/DTS, with a licence check.
   - Real-device tuning: threads, big-core pinning, and a battery mode.

## Privacy and licences

- No analytics, no accounts, no backups (`allowBackup=false`). The only permission is
  INTERNET, used for model downloads.
- If a cloud feature is ever added, it will be off by default, and its key will be stored
  with Android Keystore.
- Every dependency is MIT, Apache-2.0, BSD-2-Clause or zlib, with **no GPL or non-free
  code**. See
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
  [docs/dependencies-release.txt](docs/dependencies-release.txt). The licence texts are
  also shown in the app.
- Test media (`test-media/`) is gitignored and stays on this PC.
