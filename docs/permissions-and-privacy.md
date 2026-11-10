# Permissions, data handling and Play notes (version 1.2.0)

The movie player needs no permissions. The built-in browser needs the internet. Each
Live Floating Subtitles permission is asked for only when the user taps
**Start live subtitles**, after the app explains it.

## Permissions

| Permission | Used by | Why | When it is asked | If refused |
|---|---|---|---|---|
| `INTERNET` | Built-in browser | Load the web pages and videos the user opens. Nothing else in the app uses the network | Install time (normal permission) | — |
| `SYSTEM_ALERT_WINDOW` (Display over other apps) | Floating subtitles | Subtitles and the control button over YouTube and other apps | Internal audio and Microphone modes. The app explains first, then opens the system setting | Error card with **Try again** and **Use External device mode** (subtitles in the app, no overlay) |
| `RECORD_AUDIO` | Floating subtitles, browser (only when the user picks *Internal audio* or *Microphone* as the sound source) | The sound to recognise. Android requires it for playback capture as well as for the microphone | After the app's own explanation | Error card. After "Don't ask again", **Open app settings** |
| MediaProjection consent (screen-sharing dialog) | Internal audio | Required by Android for `AudioPlaybackCapture`. Only audio is captured: no virtual display is created and no picture is read | On every start (Android 14+ allows one use per consent) | Error card with **Use the microphone instead**. The app never switches to the microphone by itself |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MICROPHONE` | Floating subtitles | Keep a session running while another app is in front. The service type is `mediaProjection` and/or `microphone`, matching what is used | Install time (normal permissions) | — |
| `POST_NOTIFICATIONS` (Android 13+) | Floating subtitles | The ongoing notification with **Pause** and **Stop** | Once, before the first session | The session still runs, and the floating button still has Stop |

Not requested: storage, accessibility service, camera, location, contacts or accounts.

The browser's default source, the video's own sound, needs no permission at all. It reads
the page's video through Web Audio inside the app. There is no foreground service, no
microphone and no screen capture, and it stops when the browser screen closes.

## Data handling (for a store listing or a privacy policy)

- **Audio.** Audio is processed in memory on the device, in pieces of up to 12 seconds, and
  dropped after recognition. No raw audio, video or movie content is stored.
- **Live dialogue.** Recognised dialogue stays in memory while a session runs: the
  subtitles on screen and the last 2 to 4 lines as context. It is deleted when the session
  stops and never written to disk.
- **Movie player.** Generated subtitles are saved on the phone (SQLite), so a movie is
  processed only once. They can be deleted per movie.
- **Browser.** Bookmarks and history are stored on the phone (`browser.db`). Websites set
  their own cookies and cache in the app's WebView, as in any browser. The menu clears
  history, cookies and cache. Web pages are loaded from the sites the user opens, and
  those sites see the request as from any browser. WebView metrics reporting to Google is
  turned off (`android.webkit.WebView.MetricsOptOut`).
- **Nothing sent by the app.** No audio, subtitles, dialogue, history or report is uploaded.
  There is no account, analytics, advertising or crash-reporting SDK. All models are inside
  the APK, so nothing is downloaded either.
- **Visible indication.** While audio is processed there is always a visible sign. For
  floating subtitles: the ongoing notification, the floating button and, in Internal audio
  mode, Android's own screen-sharing indicator. In the browser: the subtitles and the status line
  over the video. **Stop** ends a session at once.
- **Session report.** The Live screen keeps a timing-only report of the last session:
  - Included: phone model, Android version, counts, delays, how busy and how hot the phone
    was, and why the session ended.
  - Not included: subtitle text, audio or app names.
  - It stays on the phone. It leaves only if the user taps **Copy report** and pastes it
    somewhere.
- **Logs.** The app logs timings and counts per line, not dialogue text. Text reaches the
  system log only if a developer turns that on over USB debugging
  (`adb shell setprop log.tag.LiveEngine DEBUG`, and likewise `WhisperAsr` and `Browser`).
  The setting is lost at reboot.

## What the app does not do

- It does not get around DRM. Protected video (Widevine) plays as it normally does in
  WebView, and its sound is not captured.
- It does not get around capture opt-outs. Apps that set `allowAudioPlaybackCapture=false`
  give silence. The app notices this, says so, and offers the microphone only after the user
  agrees.
- It does not download or save streams, run hidden players, or read cross-origin media that
  a site does not share (CORS). For such videos, the app explains and offers the other sound
  sources.
- It never turns on the microphone without the user's choice. Microphone use always shows
  the ongoing notification.

## Google Play notes (not a promise of approval)

- `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `FOREGROUND_SERVICE_MICROPHONE` need the
  foreground-service declaration in Play Console, with a short video of the feature in use.
  Use case: "live captions and translation of media the user is watching, started by the
  user, with an ongoing notification".
- `SYSTEM_ALERT_WINDOW` is accepted for apps whose core feature is an overlay. Live mode is
  optional, so describe it in the listing.
- The Data safety form, from the points above:
  - No data collected or shared by the developer.
  - Data is processed on the device only.
  - The browser connects to the sites the user opens.
- **Size.** The APK is about 816 MB with every model inside. Play's base-module limit is 200
  MB (AAB). Before uploading, the models must move into Play Asset Delivery install-time
  asset packs (each at most 1.5 GB, 4 GB in total). This has not been done yet. See the
  README, section *Google Play*.
- The app does not use AccessibilityService.
- Approval is decided by Google; nothing here guarantees it.
