# Live Floating Subtitles: compatibility report (version 1.1.0)

**Everything below was measured on one Android emulator on this PC. No real phone was
available.** A result here shows that the feature works on that Android build with those
app versions. It does not show that it works on any particular phone, Android skin or app
version, and none of it is a promise that YouTube or any other app allows capture.

## Test setup

| | |
|---|---|
| Device | Android emulator, AVD `SubtitleTest`: Android 16 (API 36), x86_64, 6 vCPU (AVX2), 4 GB RAM, 1080 × 2400 |
| Build | 1.1.0 release APK (versionCode 3), release key |
| Models | Whisper base (q5_1) + M2M100 418M int8, chosen automatically; Fast quality; source language "Detect automatically" |
| Apps | Google Photos 7.27.0, YouTube 20.10.41 (no Google account signed in) |
| Test audio | `fleurs_e2e.mkv`: 16 public FLEURS utterances in 8 languages (es, ja, ko, fr, zh, ru, de, it), 4:17 |

## Capture by app

| App | Mode | Result |
|---|---|---|
| Google Photos 7.27 (local video) | Internal audio | **Works.** Audio captured (`usage=USAGE_MEDIA`), subtitles shown over the player. Panel showed 2.2–3.3 s average delay |
| YouTube 20.10.41 (Spanish live news, plus an English ad before it) | Internal audio | **Works on this emulator.** Not blocked. Final lines a median 6.0 s after the speech (max 14.3 s), provisional text 2.3 s; 10 of 13 final lines shown in time. Slower than Photos because the emulator decodes the live video in software on the same CPU |
| An app that opts out of capture (`allowAudioPlaybackCapture=false`) or plays DRM audio | Internal audio | **Not tested end to end**: no such app on the emulator. The detection (only exact digital silence for ≥ 4 s while Android reports media playing) is unit-tested; the "Use microphone" confirmation in the panel is not |
| Netflix, Prime Video, Disney+ and other DRM apps | — | Not installed, not tested. They are expected to block capture, and the app does not try to get around it |
| Any app | Microphone | AudioRecord opens (`VOICE_RECOGNITION`, 16 kHz mono, not silenced) with a microphone-only foreground service. The emulator's microphone receives no speech, so recognition from a real room was **not** tested |
| Any app | External device | Big subtitles in the app, no overlay window; tested with the test file |

YouTube's behaviour can differ by app version, by video (some content, such as music or
paid films, may be protected) and by phone. Check it on a real phone before saying it works.

## Speed and quality (test file, test hook)

The test hook (`am start ... --es test_audio fleurs_e2e.mkv`) feeds the file to the live
pipeline in real time, as if it were captured audio.

| Measure | Result |
|---|---|
| Recognition per piece of speech (1.2–6 s) | median 0.8 s for final lines, 0.35 s for provisional ones (max 1.8 s) |
| Translation (M2M100) per line | median 1.2 s (max 4.8 s) |
| End of speech → final line on screen | median 4.6 s (max 11.6 s); 35 of 38 final lines shown, 3 dropped as more than 10 s late |
| End of speech → provisional line on screen | median 1.5 s |
| Translation quality, chrF (same rule as the Windows app) | **35.1**, 15 of 16 utterances found. Full Movie mode with the same models: 42.3 |
| chrF per language | de 38.5, es 46.9, fr 26.8, it 33.1, ja 17.2, ko 40.1, ru 29.3, zh 41.6 |

Why live mode scores lower than Full Movie mode:
- Each line is recognised from at most 6 s of audio (Fast), with little context, and long
  sentences are cut at the quietest point.
- Language detection on short pieces is weaker. Two German lines were detected as English
  and Latin, so they were not translated.
- The test movie changes language every sentence, which is harder than a real film.

## Overlay checks

| Check | Result |
|---|---|
| Subtitles over the home screen, Photos and YouTube | Shown: white text, black outline, translucent box, at most 2 lines |
| Touch-through | A tap on the subtitle text reached Photos (its controls hid). The window has `FLAG_NOT_TOUCHABLE` and alpha 0.8 (Android 12+ limit for pass-through) |
| Rotation to landscape and back | Subtitles and button re-laid out for the new size |
| Floating button and panel | Pause → "Paused", no recognition; Resume → lines again; Move → dashed frame, drag moved the box; Smaller and Larger present; Stop removes both windows, stops the service and releases the projection |
| Notification | "Live subtitles are on" with Pause and Stop; Stop from the notification ends the session |
| Android's screen-sharing indicator | Shown (status-bar chip) for the whole Internal-audio session |

## Refusal paths

| Refused | What the app does (checked) |
|---|---|
| Screen-sharing confirmation (Cancel) | Error card, with **Use the microphone instead**. The app does not switch to the microphone by itself |
| Display over other apps | Explanation, then the system setting; on Cancel, an error card with **Try again** and **Use External device mode** |

## Bugs found by these tests and fixed

- **No subtitles at all:** whisper.cpp encoded a fixed 30 s window for every 1.6–6 s piece
  (4–6 s per call on the emulator). The encoder now reads only the length of the piece
  (Live settings, Developer, **Short audio window**, on by default). Recognition: 4–6 s → 0.35–0.8 s.
- **Every line looked 30–150 s late and was dropped:** audio time started when capture
  started, but the subtitle clock started at session start, before the models loaded.
  Line times are now session times.
- **Subtitles wrapped at a third of the screen** (and 3 lines instead of 2): Android measures a
  wrap-content window at its dialog width (320 dp) first. The window is now full width
  (it ignores touches, so this costs nothing).
- **Status notices ("Paused") were smeared:** the yellow colour also coloured the outline.

## Not tested

- Any real phone: ARM speed, battery drain, heat during a long session, memory on a 4 GB phone.
- Android 10–15 (only Android 16 was run), and phone makers' skins (One UI, MIUI/HyperOS,
  ColorOS), which may restrict overlays, background services or capture differently.
- An app that blocks capture, DRM apps, and YouTube on a real phone.
- The microphone with real speech in a room; Bluetooth headphones; split screen and
  picture-in-picture players; displays with a real cutout.
- Auto-stop after 10/30/60 minutes and sessions longer than a few minutes.
- High Accuracy quality on live audio.

The real-phone steps are in [real-device-test-plan.md](real-device-test-plan.md#live-floating-subtitles).
