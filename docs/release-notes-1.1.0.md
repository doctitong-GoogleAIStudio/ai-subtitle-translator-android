# AI Subtitle Translator 1.1.0: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 3. Signed with the same release key as
1.0.0, so it installs over 1.0.0 and keeps recent movies and saved subtitles.

## New: Live Floating Subtitles

English subtitles over YouTube and other video apps, made on the phone from the sound they
play. Open **Live subtitles** on the main screen.

- **Three audio sources:**
  - Internal audio: Android's playback capture, with a screen-sharing confirmation.
  - Microphone: for a video on a TV or laptop.
  - External device: big subtitles in the app.
- **Floating subtitles:**
  - White text with a black outline on a translucent box, 1–2 lines.
  - Touches pass through to the video app.
  - Movable, with adjustable size.
  - Follows rotation and display cutouts.
- **Floating CC button:** Pause/Resume, Move, Smaller/Larger, Open app and Stop. The
  notification also has Pause and Stop.
- **Translation modes:**
  - Fast: provisional lines while the speech goes on.
  - High Accuracy: longer lines, more context and careful decoding.
- **Language:** detected automatically, with the confidence shown, or set by hand.
- **Context and timing:**
  - Recent lines are given to the recogniser as context.
  - Names are kept spelled the same way.
  - Repeated lines are merged.
  - A delay setting (−3 to +10 s) matches the subtitles to the picture.
- **Dashboard:**
  - Live status: capture, language, recognition, translation, overlay.
  - Latency for each stage.
  - Session usage. The estimated cost is always 0, because nothing runs in the cloud.
- **Live settings:** appearance, model and translator, delay, noise suppression,
  microphone gain, auto-stop, and Developer options (latency readout, short audio window).
- **Privacy:**
  - Audio is processed in memory and never saved.
  - There is still no INTERNET permission.
  - Dialogue text is not logged unless a developer turns it on over USB.

## Other changes

- Settings has a link to Live settings.
- About mentions how live audio is handled.
- whisper.cpp can now encode only the length of short audio. The player is unchanged and
  still uses the full window.

## New permissions

These are asked for only when a live session starts. The movie player needs none of them.

- Display over other apps.
- Record audio, which Android also requires for playback capture.
- Foreground service, with the media projection and microphone types.
- Notifications (Android 13+).

Details and Play Store notes: [live-permissions-disclosure.md](live-permissions-disclosure.md).

## Tested

Tested on the Android emulator only (Android 16, x86_64). See
[live-compatibility-report.md](live-compatibility-report.md). In summary:
- Capture worked with Google Photos and with YouTube 20.10.41.
- On the FLEURS test movie, chrF was 35.1.
- Final lines appeared a median 4.6 s after the speech, provisional lines 1.5 s.
- Overlay, controls and permission refusals were checked by hand.
- 37 unit tests pass.

## Known limitations

- **No real phone has been tested.** Speed, battery, heat and phone makers' skins are unknown.
- **Apps can block capture.** DRM films (Netflix and similar), some apps and some phones
  refuse playback capture. The app detects this, says so and offers the microphone. It
  never switches by itself and never tries to get around the block.
- **YouTube** worked on the emulator, but that does not guarantee it works on every phone,
  app version or video.
- **Android asks for the screen-sharing confirmation every time** Internal audio starts.
- **Live translation is less accurate than the movie player** (chrF 35 against 42), because
  it hears only a few seconds at a time. Short pieces are sometimes detected as the wrong
  language, so set the source language when you know it.
- **Subtitles lag the speech** by about 1.5–6 s on the emulator. M2M100 translation is the
  slowest part there. A negative delay cannot make them earlier than that.
- **Microphone mode** depends on the room. It works less well from the phone's own speaker.
- **No cloud mode.** Everything runs on the phone, and no API key is used or asked for.
- **App bundle (AAB):** built and signed (`AI-Subtitle-Translator.aab`, 791 MB), but too
  large for Google Play as it is: the models would first have to move to Play Asset
  Delivery packs. The universal APK is the release file.
