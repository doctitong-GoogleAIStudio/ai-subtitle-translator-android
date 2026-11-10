# AI Subtitle Translator 1.2.0: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 8. Signed with the same release key, so it
installs over 1.0 to 1.1.4 and keeps recent movies and saved subtitles. The movie player is
the same as in 1.1.4.

**Everything new below was tested on the Android emulator only, not on a phone.** Each
feature's results and limits are listed; nothing is claimed beyond them.

## New: built-in movie browser

**Open the browser** on the main screen opens a web browser inside the app. It has tabs, an
address bar that also searches, bookmarks, history and full-screen video. Play a video on a
page and tap **Start AI subtitles**: English subtitles appear over the video, also in full
screen.

Where the subtitles come from (**Sound source** in the menu):
1. **Automatic** (default): the video's own captions when it has a track the app can read,
   otherwise the video's sound.
2. **The video's captions (translated):** the page's caption cues are translated to English
   on the phone and shown at the captions' own times. Nothing is recognised from sound.
3. **The video's sound in this browser:** the sound is read inside the page, with no
   permission, no microphone and no screen capture. The first tap on the video lets the app
   hear it.
4. **Internal audio** or **Microphone:** only when the user picks them, each after its own
   explanation and Android's permission prompt.

**Smart Delay** (captions only, in the menu): 0, 5, 10, 15, 20 or 30 s. The video waits
until that much has been translated ahead, and waits again if translation falls behind. A
tap on Play overrides it.

What the browser does not do:
- It does not get around DRM. Protected video plays, but its sound is not read.
- It does not read video or captions that another site refuses to share. The app explains
  this and offers the other sources.
- It does not download streams or play anything hidden.

### Measured (emulator, a local test page with a public FLEURS test video)

| Check | Result |
|---|---|
| Subtitles from the video's sound | 38 lines, all shown; median delay 2.8 s after the speech, slowest 4.7 s |
| Their quality | chrF 41.3 (the test's reference translations; slightly flattering, because the time shift that lines up the session with the video was chosen for the best score) |
| Seek and pause | Old lines were cleared after a seek; nothing appeared while paused |
| Translated captions (Spanish test track) | 0.28 s per line; lines shown a median 0.07 s after the caption's own start |
| Smart Delay, 10 s | Held the video until 10 s was translated, then played on |
| Full screen | Translated captions shown in landscape full screen. With the video's sound as the source, not confirmed: the screenshot fell between two lines |
| A video or caption track from another site without sharing (CORS) | Not read; an explanation offers the other sources |

## Back: Live Floating Subtitles

The live subtitles over other apps (YouTube and others), removed in 1.1.1, are back. They
use internal audio, the microphone, or an external device with big subtitles in this app.
1.1.0 did not work as expected on a phone, and what failed was not known, so this version
adds:

- **A session report.** After each session, the Live screen shows a short report:
  - Included: how much sound was heard, how many lines were made, shown or skipped, how late
    they were, how busy and how hot the phone was, and why the session ended.
  - Not included: subtitle text, audio or app names.
  - **Copy report** copies it, to send with a few words about what you saw.
- **Keeping up on slow or warm phones.** The lines still being spoken (shown in italics)
  cost about as much work again as the finished lines. They now pause while the phone is
  busy or warm, or while finished lines wait. They come back after 30 s of calm.
- **Saying what is wrong.** The floating subtitles now say so when:
  - nothing has been heard for 8 seconds;
  - lines were skipped because the phone was busy;
  - the playing app does not allow capture (as in 1.1.0).
  A phone that refuses the floating window now gets an explanation instead of a crash.

### Measured (emulator, the FLEURS test file fed in real time)

| Setup | Lines shown | Too late | Median delay | chrF |
|---|---|---|---|---|
| Normal, 4 threads | 37 of 37 | 0 | 1.6 s | 34.8 (1.1.0: 35.1) |
| Slow phone stand-in, 1.1.0 behaviour (1 thread, 6 busy loops) | 24 of 33 | 9 | 8.1 s | 32.2 |
| Same, 1.2.0 | 31 of 34 | 3 | 6.5 s | 32.7 |

The "slow phone" is the emulator held back on purpose. It is not a measurement of any real
phone. A silent microphone showed "Listening, but the microphone hears nothing" in the
floating subtitles.

## Better: context in translated captions

- A sentence split over two or three caption cues is translated as one sentence, and its
  English is shared out over those cues. On 24 test sentences in Spanish, French and German,
  each split over two cues, chrF rose from 68.5 to 74.4. This is an offline check with the
  app's own M2M100 model. The sentences and their references were written for the test.
- Names in the English keep one spelling during a session, as the live subtitles already
  did.

## Movie player: unchanged, checked again

The 1.1.4 checks, rerun with 1.2.0 on the emulator:

| Check | 1.1.4 | 1.2.0 |
|---|---|---|
| Film clip, normal setup: long blanks | 2, totalling 3 s | 2, totalling 3 s |
| Film clip, slow setup (1 thread) | 2, totalling 3 s | 3, totalling 5 s |
| Film clip, slowest setup (Whisper small translating) | 10, totalling 31 s | 5, totalling 16 s |
| FLEURS test movie, chrF | 43.6 (31 lines) | 42.5 (32 lines), 0 of 16 missing |

The player's recognition, translation and look-ahead code is the same as in 1.1.4. The
live subtitles' new Whisper options are off for it, and its developer test settings now need
the test switch. So the differences are run-to-run variation. In FLEURS, five languages scored
exactly the same, and one utterance in each of German, French and Italian was split
differently.

## Permissions

1.2.0 adds `INTERNET`, for the browser only. Floating subtitles bring back display over
other apps, record audio, the foreground-service types and notifications, each asked for
only when a live session is started. Details, data handling and Play notes are in
[permissions-and-privacy.md](permissions-and-privacy.md). The app still sends nothing
anywhere itself: no analytics, accounts or uploads.

## Known limitations

- **No real phone yet.** Real-phone speed, battery and heat are unmeasured for everything
  above. So are YouTube and other apps on a phone, phone skins (One UI, HyperOS, ColorOS)
  and Android 8 to 15.
- **Sound from many sites cannot be read.** Videos served from another site without
  sharing (common for big streaming sites) and DRM video cannot be read in the browser.
  Use their captions, or the microphone.
- **Live subtitles are less accurate than the movie player**, which has the whole film to
  work with: FLEURS chrF 34.8 live, 43.6 in the player's Full Movie mode.
- **Google Play:** the APK (816 MB) is too big for Play, so there is a separate Play bundle
  with the models in an install-time asset pack (`AI-Subtitle-Translator-play v1.2.0.aab`;
  39 MB base, 732 MB pack). It installed and played the FLEURS test movie on the emulator
  through bundletool (chrF 43.2). It has not been uploaded to Play.
