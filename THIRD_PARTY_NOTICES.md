# Third-party notices: AI Subtitle Translator for Android

Publisher: aivicventures. The APK contains only the components below. Every licence is
permissive (MIT, Apache-2.0, BSD-2-Clause or zlib) except FFmpeg's audio decoders, which are
LGPL 2.1 or later and kept as separate, replaceable shared libraries (see below);
**no GPL or non-free code is included**. The full
licence texts ship inside the APK (`assets/licenses/`) and are shown in the app under
**About & licences**.

## Code inside the APK

| Component | Version | Licence | Use |
|---|---|---|---|
| whisper.cpp + ggml (ggml-org) | v1.9.5 (source vendored in `third_party/whisper.cpp`, unmodified) | MIT | speech recognition and translation to English, Silero VAD runtime |
| CTranslate2 (OpenNMT / SYSTRAN) | v4.8.3 (vendored in `third_party/ctranslate2`, unmodified; 64-bit ABIs only) | MIT | M2M100 text translation (`libctranslate2.so`, `libsubtrans_mt.so`) |
| Ruy (Google), bundled in CTranslate2 | commit `363f2522` | Apache-2.0 | int8 and float matrix multiplication for CTranslate2 |
| cpuinfo (PyTorch / Facebook, Georgia Tech) with clog, bundled in Ruy | commit `082deffc` | BSD-2-Clause | CPU feature detection for Ruy |
| cpu_features (Google), bundled in CTranslate2 | commit `8a494eb1` | Apache-2.0 | CPU feature detection for CTranslate2 |
| spdlog (Gabi Melman) with bundled {fmt} (Victor Zverovich) | commit `76fb40d9` | MIT | logging inside CTranslate2 |
| BS::thread_pool (Barak Shoshany), neon/avx mathfun headers (Julien Pommier; Giovanni Garberoglio; Marian NMT), avx512_mathfun (SIMD_Utils) | bundled in CTranslate2 | MIT; zlib; BSD-2-Clause | thread pool and vectorised math in CTranslate2 |
| AndroidX Media3 (exoplayer, ui, inspector, common, extractor, decoder, container, datasource, database) | 1.11.1 | Apache-2.0 | video playback; demuxing audio for recognition (`MediaExtractorCompat`) |
| AndroidX core-ktx, activity-ktx, lifecycle-runtime-ktx and their AndroidX dependencies (annotation, collection, savedstate, recyclerview, profileinstaller, startup, tracing, compose runtime-annotation, …) | see `gradle/libs.versions.toml` | Apache-2.0 | app framework |
| Material Components for Android (Google) with AppCompat, ConstraintLayout core, CoordinatorLayout, CardView, DrawerLayout, ViewPager2, Transition, DynamicAnimation, Fragment, ResourceInspection and other AndroidX libraries it pulls in, plus Error Prone annotations | 1.14.0 | Apache-2.0 | Material 3 look: cards, buttons, switches, sliders, chips, dialogs, app bar |
| Material Icons (Google): path data of 19 icons in `res/drawable/ic_*.xml` | — | Apache-2.0 | icons on the screens |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization-core (pulled in by AndroidX), JetBrains annotations | Kotlin (AGP 9.4.1 built-in), coroutines 1.11.0 | Apache-2.0 | language runtime, background work |
| Guava (pulled in by Media3) with failureaccess, listenablefuture, JSpecify | per Media3 1.11.1 | Apache-2.0 | utility library used by Media3 |
| FFmpeg (libavcodec, libavutil, libswresample) | 7.1.5, unmodified (see "FFmpeg" below) | LGPL 2.1 or later | AC-3, E-AC-3, DTS, TrueHD/MLP and MPEG audio decoders, used only when the phone has no decoder of its own |
| Media3 FFmpeg decoder extension (`decoder_ffmpeg`: `FfmpegAudioRenderer` and its JNI glue `libffmpegJNI.so`) | 1.11.1 (source vendored in `app/src/main/java/androidx/media3/decoder/ffmpeg` and `app/src/main/cpp/media3_ffmpeg`, unmodified) | Apache-2.0 | playing those audio formats |
| LLVM libc++ (`libc++_shared.so` from Android NDK r27) | NDK 27.1.12297006 | Apache-2.0 WITH LLVM-exception | C++ runtime for the native code |

The exact resolved dependency list of a build is printed by
`gradlew :app:dependencies --configuration releaseRuntimeClasspath` (see `docs/dependencies-release.txt`).

## Models (bundled in the APK)

| Model | Source (pinned commit) | Licence |
|---|---|---|
| Whisper tiny / base / small, ggml q5_1 | `huggingface.co/ggerganov/whisper.cpp` @ `5359861c739e955e79d9a303bcbc70fb988958b1` | MIT (OpenAI Whisper; conversion by the whisper.cpp authors) |
| Silero VAD v6.2.0, ggml | `huggingface.co/ggml-org/whisper-vad` @ `9ffd54a1e1ee413ddf265af9913beaf518d1639b` | MIT (Silero Team) |
| M2M100 418M, CTranslate2 int8 (model.bin, shared_vocabulary.json, config.json, sentencepiece.bpe.model) | `huggingface.co/jncraton/m2m100_418M-ct2-int8` @ `7c1b2620a4e58dacecbd8bf89cfd6da7eb9eb7b0` | MIT (Meta AI `facebook/m2m100_418M`; conversion by Jon Craton) |

Every model is redistributed inside the APK under its MIT licence; the licence texts are in
`assets/licenses/` (`OpenAI-Whisper-MIT.txt`, `Silero-VAD-MIT.txt`, `M2M100-MIT.txt`). The
build fetches each file from the pinned source above and stops unless its size and SHA-256
match `app/src/main/assets/model_catalog.json` (`fetchModels` in `app/build.gradle.kts`).

## Considered and not used

| Component | Why not |
|---|---|
| Google ML Kit on-device translation | proprietary SDK (no reverse engineering, sends usage metrics to Google), models cannot be pinned by checksum |
| NLLB-200 | CC-BY-NC 4.0 weights (non-commercial) |
| ONNX Runtime (MIT) with the `Xenova/m2m100_418M` ONNX export | measured on the PC before choosing: the int8 export turns its whole 128k-word embedding back into floats on every token (about 116 ms per token and 1.2 GB of RAM); the 4-bit export needs 2.2 GB. CTranslate2 with the same weights on the same PC: 0.5 GB and about 10–15 ms per token |
| sentencepiece library | not needed: `translate/SentencePieceBpe.kt` reads the model file itself and is tested against sentencepiece 0.2.2's output |
| FFmpeg with GPL or non-free parts, or any FFmpeg demuxer, encoder, filter or network code | not needed: the build enables only six audio decoders and refuses any configuration whose licence is not LGPL |
| Checker Framework qualifiers (`checker-qual`, MIT) | compile-time annotations for the vendored Media3 code; nothing of it is in the APK |

## FFmpeg

The APK contains `libavcodec.so`, `libavutil.so` and `libswresample.so` from FFmpeg 7.1.5,
built from the unmodified release tarball
`https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz`
(SHA-256 `de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f`) by
`scripts/build_ffmpeg.sh`, which records the exact configure line per ABI in
`app/src/main/ffmpeg/configuration-<abi>.txt`. FFmpeg is licensed under the GNU Lesser
General Public License, version 2.1 or later; its text ships as
`assets/licenses/FFmpeg-LGPL-2.1.txt`.

- Only the decoders `ac3`, `eac3`, `dca`, `truehd`, `mlp` and `mp3` are enabled, with
  `--disable-everything --disable-autodetect --disable-network` and without `--enable-gpl`,
  `--enable-version3` or `--enable-nonfree`. The build script stops unless FFmpeg's own
  configuration reports "LGPL version 2.1 or later", and the app disables the decoders at run
  time unless `avcodec_license()` reports LGPL.
- The libraries are dynamically linked and stored uncompressed under `lib/<abi>/` in the APK.
  You may replace them with your own build of FFmpeg 7.1 (same library names and ABI): rebuild
  with `scripts/build_ffmpeg.sh`, or drop your `.so` files into
  `app/src/main/ffmpeg/jniLibs/<abi>/` and run `gradlew :app:assembleRelease`. The app's
  source is in this repository, so it can be rebuilt against a modified FFmpeg.
- The source ships inside the APK, so sharing the APK also shares it:
  `assets/source/ffmpeg-7.1.5-source.zip` holds the unmodified tarball above, the build script
  and the configure line of each ABI (`ffmpegSource` in `app/build.gradle.kts`, which checks
  the tarball's SHA-256). In the app, **About & licences → Save FFmpeg source code** copies it
  to a folder of your choice.
- Patents: AC-3, E-AC-3 and DTS are covered by patents in some countries. The LGPL does not
  grant patent rights; check the rules where you distribute the app.
