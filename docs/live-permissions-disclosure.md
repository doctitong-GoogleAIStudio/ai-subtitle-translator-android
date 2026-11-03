# Permission disclosure checklist (Live Floating Subtitles)

Version 1.1.0 adds the permissions below. The movie player needs none of them. Each is asked
for only when the user taps **Start Live Subtitles**, after an explanation in the app.

| Permission | Why | When it is asked | If refused |
|---|---|---|---|
| `SYSTEM_ALERT_WINDOW` (Display over other apps) | Floating subtitles and control button over YouTube and other apps | Internal audio and Microphone modes; the app explains, then opens the system setting | Error card with **Try again** and **Use External device mode** (subtitles in the app, no overlay) |
| `RECORD_AUDIO` | The sound to recognise. Android requires it for playback capture as well as for the microphone | Every mode, after the app's consent dialog | Error card; after "Don't ask again" it offers **Open app settings** |
| MediaProjection consent (screen-sharing dialog) | Required by Android for `AudioPlaybackCapture`. Only audio is captured; no virtual display is created and no picture is read | Internal audio mode, on every start (Android 14+ allows one use per consent) | Error card with **Use the microphone instead** |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MICROPHONE` | Keep the session running while another app is in front. The service type is `mediaProjection` and/or `microphone`, matching what is used | Install-time (normal permissions) | — |
| `POST_NOTIFICATIONS` (Android 13+) | The ongoing notification with **Pause** and **Stop** | Once, before the first session | The session still runs. The notification shows only in the system's task manager, and the floating button still has Stop |

Not requested: `INTERNET` (none at all), storage, accessibility service, camera, location, contacts.

## Data handling (for a store listing or a privacy policy)

- Audio is processed in memory on the device, in pieces of up to 12 seconds, and dropped
  after recognition. No raw audio, video or movie content is stored.
- Recognised dialogue is kept in memory while a session runs (the subtitles on screen and the
  last 2–4 lines as context). It is deleted when the session stops and never written to disk.
- Nothing is sent anywhere: the app has no network permission, so no third party receives
  audio or text.
- While audio is processed there is always a visible indication: the ongoing notification,
  the floating button and, in Internal audio mode, Android's own screen-sharing indicator.
  **Stop** in either the notification or the overlay ends the session at once.
- Logs: the app logs timings and counts per line, not dialogue text. Text is written to the
  system log only after a developer turns it on over USB debugging
  (`adb shell setprop log.tag.LiveEngine DEBUG`, likewise `WhisperAsr` for dropped
  hallucinations); the setting is lost at reboot.

## Google Play notes (not a promise of approval)

- `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `FOREGROUND_SERVICE_MICROPHONE` need the
  foreground-service declaration in Play Console, with a short video of the feature in use.
  Use case: "live captions/translation of media the user is watching, started by the user,
  with an ongoing notification".
- `SYSTEM_ALERT_WINDOW` is allowed for apps whose core feature is an overlay. The Live mode
  is optional, so describe it in the listing.
- The app does not use AccessibilityService and does not get around capture opt-outs or DRM.
- Approval is decided by Google; nothing here guarantees it.
