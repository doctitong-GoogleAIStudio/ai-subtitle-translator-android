FFmpeg source code for AI Subtitle Translator
=============================================

The app contains libavcodec.so, libavutil.so and libswresample.so from FFmpeg 7.1.5,
licensed under the GNU Lesser General Public License, version 2.1 or later. This archive is
their complete source:

- ffmpeg-7.1.5.tar.xz: the unmodified release tarball from
  https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz
  (SHA-256 de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f).
- build_ffmpeg.sh: the script that built the libraries with the Android NDK.
- configuration-<abi>.txt: the exact configure line and FFmpeg's licence report for each
  processor type (arm64-v8a, armeabi-v7a, x86_64, x86).

Only the audio decoders ac3, eac3, dca, truehd, mlp and mp3 are enabled, without
--enable-gpl, --enable-version3 or --enable-nonfree.

The libraries are separate shared libraries in the APK (lib/<abi>/). You may replace them
with your own build of FFmpeg 7.1 that has the same library names and ABI. Because the app
links to them dynamically (LGPL 2.1, section 6b), it uses a replacement without being
rebuilt; only the APK has to be signed again.
