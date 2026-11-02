# AI Subtitle Translator (Android)

Android version of the Windows app *Universal AI Movie Subtitle Translator*. It plays your
own video files and shows English subtitles generated **on the phone**: Whisper speech
recognition, translation to English (M2M100 418M or Whisper's own translate task, whichever
measured better for the chosen speech model), a look-ahead buffer that stays ahead of the
playhead, a cache so a movie is never processed twice, SRT export, an audio track picker
and optional LGPL FFmpeg decoders for AC-3, E-AC-3 and DTS soundtracks. No movie audio,
subtitles or keys leave the device. Every model is built into the app, so nothing is
downloaded, and the app has no internet permission at all.

Status: **version 1.0.0**, release build (see [Phases](#phases)). Package
`com.aivicventures.subtitletranslator`.

## The APK

`AI-Subtitle-Translator-universal.apk` in this folder is the universal release build,
version 1.0.0:
- **Size:** about 816 MB, because every model is inside: Whisper tiny, base and small,
  Silero VAD, and the M2M100 translator (778 MB together). FFmpeg's source code is inside
  too (11 MB), so the APK can be shared on its own. The app itself is 26 MB.
- **Phones:** arm64-v8a, armeabi-v7a, x86_64 and x86, Android 8.0 (API 26) or later.
- **Free space:** installing needs well over 1 GB free, because Android keeps a reserve; an
  emulator here refused it at 1.3 GB free. Once installed the models take no second copy,
  because they are read straight from the APK.
- **Signing:** signed with this project's own release key (see
  [Release signing](#release-signing)), not the debug key.
- **Upgrading from 0.1:** earlier builds were signed with the debug key, so Android will not
  install 1.0 over them. Uninstall the old app first. This also clears its recent list and
  saved subtitles, and the models it had downloaded.

Install it with `adb install -r AI-Subtitle-Translator-universal.apk`, or by copying the
file to the phone and opening it.

Use: tap **Open a video**, pick a file, and playback starts by itself once enough subtitles
are ready. There is nothing to download or set up first.

## Using it

- **Look-ahead modes** (Settings):
  - Fast: 5 s ahead.
  - Balanced: 10 s ahead (default).
  - High Accuracy: 30, 45 or 60 s ahead, with temperature fallback.
  - Full Movie: processes everything first.
- **Full screen**: widescreen videos open rotated to landscape and fill the screen
  (Settings, Player, on by default). The full-screen button in the player's controls
  switches between landscape and portrait. **Fill** in the top bar crops the picture to
  cover the whole screen; **Fit** shows all of it again.
- **Recent movies**: tap a movie to continue where you stopped. The card shows how far you
  watched and how much of it has subtitles. Its **⋮** menu has:
  - **Play from the beginning**.
  - **Remove from list**: the subtitles are kept, so opening the movie again is instant.
  - **Delete saved subtitles**: removes the movie from the list and deletes its subtitles
    and its audio track choice.
  - Neither touches the video file itself.
- **Built-in models** (menu): lists the models in the app, their size and licence, and which
  speech model this phone uses automatically (by its memory).
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
- **Audio** (top bar, shown when the movie has more than one audio track): picks the track
  that is played **and** recognised, for example the original language instead of a dub.
  The choice is remembered per movie, and each track has its own subtitle cache. Without a
  choice, the app takes the first track it can decode in the movie language set in
  Settings, else the first track it can decode. Tracks the phone cannot decode are marked.
- **AC-3, E-AC-3, DTS, TrueHD**: many phones have no decoder for these. The app then uses
  its own FFmpeg decoders, both for playback and for recognition. The phone's own decoders
  are always tried first. About & licences shows the FFmpeg version and licence, and
  **Save FFmpeg source code** there saves FFmpeg's source (built into the app) to the phone.
- **Use only the fast CPU cores** (Settings, on by default): keeps recognition and
  translation off the slow power-saving cores of big.LITTLE phones; one thread on a slow
  core holds all the others back. The fast cores come from the kernel's `cpu_capacity`
  (speed × work per cycle), or from top frequencies on older kernels. The default thread
  count is the number of fast cores, at most 4. The switch is greyed out on phones whose
  cores are all alike.
- **Battery saver** (Settings, off by default): half the threads, greedy M2M100 decoding and
  no background work beyond the look-ahead (Full Movie mode still finishes). It also turns
  on by itself, re-checked before every 30 s job:
  - when the system Power Saver is on;
  - when the battery is at 15 % or less and not charging;
  - when the phone reports itself hot (thermal status SEVERE).
  At thermal status CRITICAL it drops to one thread and pauses 10 s between jobs. The
  status line shows why it is saving.
- **Source language**: "Detect automatically" is the default. A fixed language is used
  as given.
- **Translation** (Settings):
  - Automatic (default): M2M100 with Whisper tiny and base, Whisper's own translation
    with small. This follows the FLEURS results below.
  - M2M100 always.
  - Whisper only: no extra model, fastest.
  - On a 32-bit phone, Whisper translates (M2M100 needs a 64-bit phone).
  - Each choice has its own cache, so lines from the two translators are never mixed.

## Building

You need Android Studio's bundled JBR (Java 21), Android SDK 37, NDK 27.1.12297006 and
CMake 3.22.1.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`.

The first build downloads the models (778 MB) into `bundled-models/` (git-ignored, kept
across clean builds):
- The `fetchModels` task in `app/build.gradle.kts` reads `assets/model_catalog.json`.
- Every file comes from a URL pinned to a Hugging Face commit, and the build stops unless
  its size and SHA-256 match.
- The models are packaged uncompressed (`noCompress "bin"`), so whisper.cpp and CTranslate2
  read them in place through a file descriptor and offset (`cpp/subtrans_jni.cpp`,
  `cpp/subtrans_mt.cpp`). Only the small files (Silero VAD, M2M100's vocabulary, config and
  tokenizer, 6 MB) are copied to app storage on first use.

### Release signing

The release build is signed with the key in `signing/release.jks`. Its passwords are in
`keystore.properties` in the project root. Both are git-ignored and exist only on this PC.
- **Back up both files** somewhere safe. Without them no update can ever be installed over
  this app; it would have to be uninstalled first.
- Without `keystore.properties` the release build falls back to the debug key, so a fresh
  clone still builds.
- Certificate: `CN=AI Subtitle Translator, O=aivicventures`, RSA 4096, valid 30 years,
  SHA-256 `dbad8165507ccf44368333ec69de2c3e404adc18f6f01b03e3d93f3d1bed99d9`.

The FFmpeg decoders are optional and built separately, once (about 25 minutes on this PC):

```bash
bash scripts/build_ffmpeg.sh "$ANDROID_SDK/ndk/27.1.12297006" C:/ffbuild
```

- It downloads FFmpeg 7.1.5, checks its SHA-256, and builds six audio decoders (`ac3`,
  `eac3`, `dca`, `truehd`, `mlp`, `mp3`) plus libswresample as shared libraries for the four
  ABIs.
- It stops unless FFmpeg's own configuration reports "LGPL version 2.1 or later" with GPL,
  version 3 and non-free all off. It also checks that every library's SONAME is its plain
  file name, as Android requires.
- The work dir must not contain spaces; on Windows it runs in Git Bash with the NDK's
  `make.exe`.
- Output goes to `app/src/main/ffmpeg/`. `jniLibs/` and `include/` are git-ignored; the
  configure line per ABI (`configuration-<abi>.txt`) is versioned.
- It also copies `ffmpeg-7.1.5.tar.xz` to the project root (git-ignored). The release build
  packs it, the build script and the configure lines into
  `assets/source/ffmpeg-7.1.5-source.zip`, so the LGPL source travels inside the APK. The
  build stops if the FFmpeg libraries are present but the tarball is missing or its SHA-256
  differs.
- Without this step the app still builds and runs. It then has no FFmpeg fallback, and
  About says so.

The native code is vendored **unmodified**:
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
| Audio | `audio/AudioWindowReader.kt`, `Resampler.kt`, `FfmpegAudio.kt`, `cpp/subtrans_ff.cpp` | Media3 `MediaExtractorCompat` demux of the chosen track + `MediaCodec` decode of one time window, or FFmpeg when the phone has no decoder. Packets before the window are skipped without decoding. Multichannel audio is downmixed with dialogue weighting (centre 0.4, front 0.2 each, surround 0.1 each, LFE 0 in 5.1). A plain average would leave centre-channel dialogue 16 dB down. Polyphase windowed-sinc resampling to 16 kHz mono. |
| Playback of AC-3/DTS | `androidx/media3/decoder/ffmpeg/` (vendored), `cpp/media3_ffmpeg/ffmpeg_jni.cc` | Media3's own FFmpeg extension 1.11.1, unmodified, built against the same FFmpeg libraries; enabled only when the run-time licence check passes. |
| Device tuning | `device/CpuTopology.kt`, `device/PowerMonitor.kt` | Fast-core detection and thread pinning (`sched_setaffinity` on the engine thread; whisper.cpp's and CTranslate2's worker threads inherit the mask). Power state from PowerManager and the battery broadcast, before every job. |
| Recognition | `asr/WhisperAsr.kt`, `Hallucination.kt` | Pipeline below this table. |
| Translation | `translate/M2M100Translator.kt`, `SentencePieceBpe.kt`, `cpp/subtrans_mt.cpp` | Details below this table. |
| Look-ahead | `buffering/LookAheadEngine.kt`, `Coverage.kt` | Port of the Windows engine (details below this table). |
| Cache | `cache/CacheDb.kt` | Tables: movies, profiles, segments, coverage. |
| Subtitles | `subtitles/Layout.kt`, `Subtitles.kt` | Port of the Windows layout rules: 2 × 42 characters, ≤ 20 characters per second, ≤ 7 s per cue. SRT writer. |
| Models | `models/ModelManager.kt`, `assets/model_catalog.json` | All bundled in the APK (fetched and SHA-256 checked at build time). Weights read in place from the APK; default speech model chosen by device RAM. |
| UI | `ui/` | Material 3 (Material Components 1.14), light and dark, brand colours. Main (recent movies with remove/delete), Player (Media3 ExoPlayer + subtitle overlay, landscape full screen, fit/fill), Built-in models, Settings, About & licences. |

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

### Phase 3: audio tracks, FFmpeg decoders, device tuning

Test movie: the Phase 1 FLEURS movie (movie 1) remuxed with five tracks, made with the
Windows app's LGPL FFmpeg build:

| # | Track | Content |
|---|---|---|
| 1 | AAC stereo, tagged English | **silence** (a decoy, so the wrong track is easy to spot) |
| 2 | AC-3 5.1, 384 kb/s | the movie's speech |
| 3 | E-AC-3 stereo, 192 kb/s | the movie's speech |
| 4 | DTS 5.1 (FFmpeg's DTS encoder) | the movie's speech |

The emulator has no AC-3, E-AC-3 or DTS decoder, so every speech track went through the
FFmpeg path. Each run: Full Movie, Whisper small with its own translation, same APK,
scored like Phase 2.

| Track used | Decoder (recognition) | chrF | Missing / 16 | Time (4:17 movie) |
|---|---|---|---|---|
| Original movie, AAC (baseline, same APK) | `c2.android.aac.decoder` | **46.7** | 0 | 700 s ¹ |
| 2: AC-3 5.1 | `ffmpeg:ac3` | **45.6** | 1 | 554 s |
| 3: E-AC-3 | `ffmpeg:eac3` | **47.2** | 0 | 573 s |
| 4: DTS 5.1 | `ffmpeg:dca` | **45.4** | 2 | 527 s |
| 1: silent decoy | `c2.android.aac.decoder` | 0 lines | 16 | 52 s |

¹ The APK was being rebuilt on the PC during this run.

- The three lossy codecs score within ±1 of the AAC original: their compression changes
  the audio slightly, and that moves Whisper's output a little either way. The baseline
  matches Phase 2's small + Whisper result on the same movie (46.7) exactly.
- FFmpeg decoding is fast: 33 s of AC-3 in 0.5–0.8 s on the emulator, against 4–5 s for the
  platform AAC decoder.
- The decoy produced no lines at all, so recognition really uses the chosen track. Each
  track is cached separately (`translate@a1`, `@a2`, `@a3`).

| Check | Result |
|---|---|
| JVM unit tests (Phase 3: fast-core selection by capacity and by frequency, CPU list parsing, every power-saving trigger, default-track choice, track labels, downmix weights, FFmpeg codec names) | 22/22 pass |
| Licence check at build time | FFmpeg `config.h`: `FFMPEG_LICENSE "LGPL version 2.1 or later"`, `CONFIG_GPL 0`, `CONFIG_VERSION3 0`, `CONFIG_NONFREE 0`, for all four ABIs |
| Licence check at run time | Log: "FFmpeg 7.1.5, LGPL version 2.1 or later"; Media3: "Loaded FfmpegAudioRenderer" |
| Playback of the AC-3 track | Played by `ffmpegLavc61.19.101-ac3`; Android shows the app's audio track playing, 5.1 (mask `0x3f`), 48 kHz |
| Audio picker | Lists "1. English AAC stereo, 2. Unknown language AC-3 5.1, 3. Unknown language E-AC-3 stereo, 4. Unknown language DTS 5.1". Choosing 4 restarts the screen: playback switches to `ffmpegLavc61.19.101-dca` and subtitles come from DTS. After a cold restart, track 4 is still chosen |
| Default track (no choice yet) | Track 1, the first track the phone can decode (movie language on Automatic) |
| Pinning to cores 0–1 (test hook) | `sched_setaffinity` succeeded. The engine thread, its 3 whisper.cpp workers and the audio prefetch thread run on cores 0–1; the UI and player threads stay on all 6 |
| Fast-core detection on the emulator | No `cpu_capacity`; every core reports the same frequency. So the cores count as uniform, nothing is pinned, the switch is greyed out, and 4 threads is the default |
| Battery saver setting | "power: saving=true reason=battery saver setting threads=2" |
| Battery at 10 %, unplugged (`dumpsys battery`) | "reason=battery 10 % threads=2" |
| Thermal SEVERE (`cmd thermalservice override-status 3`) | "reason=phone is hot threads=2" |
| Thermal CRITICAL (status 4) | "critical=true reason=phone is very hot threads=1"; the first chunk took 110 s instead of 80 s |
| Battery and thermal state | Reset after the tests |

The emulator can show that these switches take effect. It cannot show whether they help:
- On its 6 virtual cores, the first 30 s chunk took 80 s with 4 threads and 77–80 s with 2,
  so here half the threads cost nothing.
- The emulator's cores are identical, it has no battery, and its heat is the PC's.
- Whether pinning and fewer threads save time or battery on a real big.LITTLE phone is
  **unmeasured**.

### Not tested yet

- A real ARM phone: speed, battery, heat, and memory with small on a 4 GB phone. M2M100
  speed and memory on ARM (Phase 2) are also unmeasured.
- The SRT export save dialog end to end.
- Very long files (the 2 h film was only tested as a 6-minute excerpt).
- Phase 3 on a real phone:
  - fast-core pinning and thread counts on big.LITTLE;
  - battery drain with and without the battery saver;
  - throttling during a whole film.
- The FFmpeg decoders on ARM. They were only run on x86_64, but they are the same source
  built for each ABI.
- TrueHD/MLP and MPEG audio through FFmpeg. These decoders are built but no test file was
  run.
- A real film with a dub and an original track. The multi-track test used a remuxed FLEURS
  movie.

[docs/real-device-test-plan.md](docs/real-device-test-plan.md) covers the real-phone
items. It runs from the PC with `bash scripts/device_bench.sh <suite>`, which measures speed,
memory, heat, estimated battery use and chrF. It then says which default each result
decides.

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
- **Codec patents**: AC-3, E-AC-3 and DTS are patented in some countries. FFmpeg's LGPL
  licence grants no patent rights, so check the rules where the app is distributed. A
  build without the FFmpeg step has no such decoders.
- **DTS:X, TrueHD Atmos and E-AC-3 JOC**: only their core or bed channels are decoded.
  That is enough for dialogue.
- When a saved track is not the first, the player starts the first track's decoder for a
  moment before switching. Nothing is heard, because playback starts paused.

## Phases

1. **Phase 1** (done):
   - Playback, on-device Whisper translate to English, look-ahead buffering, cache, SRT
     export, Model Manager, and licences screen.
2. **Phase 2** (done):
   - M2M100 418M for text translation after Whisper transcription.
   - It runs on CTranslate2 (MIT) with an int8 model. ONNX Runtime was planned, but
     measurements on the PC showed it needs 2–4× the memory, and the int8 export was about
     10× slower per token; see THIRD_PARTY_NOTICES.md.
   - Per-language quality comparison against the Whisper translate task (above).
   - Automatic translator choice per speech model.
3. **Phase 3** (done):
   - Audio track picker, remembered per movie, used for playback and recognition.
   - Optional LGPL-only FFmpeg decoders (AC-3, E-AC-3, DTS, TrueHD, MPEG audio) for
     recognition, and Media3's FFmpeg extension for playback, with build-time and run-time
     licence checks.
   - Device tuning: fast-core detection and pinning, default thread count, and a battery
     saver that also follows Power Saver, low battery and heat.
   - No real phone was available, so the tuning is implemented and shown to take effect,
     but not measured.
4. **Version 1.0.0 (this build)**:
   - Every model built into the APK, no downloads and no internet permission.
   - Landscape full screen with fit/fill, remove and delete in Recent movies, and a
     Material 3 look with a new launcher icon (themed icon on Android 13+).
   - Release key instead of the debug key.
5. **Next: real-phone measurements**:
   - Follow [docs/real-device-test-plan.md](docs/real-device-test-plan.md) on one or more
     phones, then adjust the defaults from the results.

## Privacy and licences

- No analytics, no accounts, no backups (`allowBackup=false`) and **no permissions**: not
  even INTERNET (the network permissions Media3 declares are removed from the manifest).
- If a cloud feature is ever added, it will be off by default, and its key will be stored
  with Android Keystore.
- The models are redistributed in the APK under their MIT licences, which are shown in the
  app.
- Every dependency is MIT, Apache-2.0, BSD-2-Clause or zlib, except FFmpeg's decoders, which
  are LGPL 2.1 or later. They are kept as separate shared libraries that can be replaced,
  and their source is built into the APK (About & licences → **Save FFmpeg source code**),
  so the APK can be shared on its own. There is **no GPL or non-free code**. See
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
  [docs/dependencies-release.txt](docs/dependencies-release.txt). The licence texts are
  also shown in the app.
- Test media (`test-media/`) is gitignored and stays on this PC.
