# Third-party notices: AI Subtitle Translator for Android

Publisher: aivicventures. The APK contains only the components below. Every licence is
permissive (MIT or Apache-2.0); **no GPL, LGPL or non-free code is included**. The full
licence texts ship inside the APK (`assets/licenses/`) and are shown in the app under
**About & licences**.

## Code inside the APK

| Component | Version | Licence | Use |
|---|---|---|---|
| whisper.cpp + ggml (ggml-org) | v1.9.5 (source vendored in `third_party/whisper.cpp`, unmodified) | MIT | speech recognition and translation to English, Silero VAD runtime |
| AndroidX Media3 (exoplayer, ui, inspector, common, extractor, decoder, container, datasource, database) | 1.11.1 | Apache-2.0 | video playback; demuxing audio for recognition (`MediaExtractorCompat`) |
| AndroidX core-ktx, activity-ktx, lifecycle-runtime-ktx and their AndroidX dependencies (annotation, collection, savedstate, recyclerview, profileinstaller, startup, tracing, compose runtime-annotation, …) | see `gradle/libs.versions.toml` | Apache-2.0 | app framework |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization-core (pulled in by AndroidX), JetBrains annotations | Kotlin (AGP 9.4.1 built-in), coroutines 1.11.0 | Apache-2.0 | language runtime, background work |
| Guava (pulled in by Media3) with failureaccess, listenablefuture, JSpecify | per Media3 1.11.1 | Apache-2.0 | utility library used by Media3 |
| LLVM libc++ (`libc++_shared.so` from Android NDK r27) | NDK 27.1.12297006 | Apache-2.0 WITH LLVM-exception | C++ runtime for the native code |

The exact resolved dependency list of a build is printed by
`gradlew :app:dependencies --configuration releaseRuntimeClasspath` (see `docs/dependencies-release.txt`).

## Models (downloaded by the user in the Model Manager, never bundled)

| Model | Source (pinned commit) | Licence |
|---|---|---|
| Whisper tiny / base / small, ggml q5_1 | `huggingface.co/ggerganov/whisper.cpp` @ `5359861c739e955e79d9a303bcbc70fb988958b1` | MIT (OpenAI Whisper; conversion by the whisper.cpp authors) |
| Silero VAD v6.2.0, ggml | `huggingface.co/ggml-org/whisper-vad` @ `9ffd54a1e1ee413ddf265af9913beaf518d1639b` | MIT (Silero Team) |

Each file's size and SHA-256 are in `app/src/main/assets/model_catalog.json`; a download is
installed only if both match.

## Considered and not used

| Component | Why not |
|---|---|
| Google ML Kit on-device translation | proprietary SDK (no reverse engineering, sends usage metrics to Google), models cannot be pinned by checksum |
| NLLB-200 | CC-BY-NC 4.0 weights (non-commercial) |
| FFmpeg decoder extension for AC-3 / DTS | planned for Phase 3, LGPL-only build with a licence check; not in this APK |
