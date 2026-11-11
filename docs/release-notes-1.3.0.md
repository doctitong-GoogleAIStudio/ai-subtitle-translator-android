# AI Subtitle Translator 1.3.0: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 9. Signed with the same release key, so it
installs over 1.0 to 1.2.0 and keeps recent movies, saved subtitles, bookmarks and history.
The movie player and the floating subtitles are the same as in 1.2.0.

**Everything new below was tested on the Android emulator only, not on a phone.** Each
feature's results and limits are listed; nothing is claimed beyond them.

## New: Smart Delay with the video's sound, on any site

In 1.2.0, Smart Delay worked only for videos with captions. Now it also works from the
video's sound, on every site whose player streams through Media Source Extensions (most
video sites, Bilibili.tv and Dailymotion among them):

1. Tap **Delay** under the browser and choose 5, 10, 15, 20, 30, 45 or 60 s (or off).
2. Play a video and tap **Start AI subtitles**.
3. The video waits ("Smart Delay: preparing subtitles, N of 15 s ready"). Meanwhile the
   app recognises and translates the sound the site's player has **already loaded ahead**
   of the picture.
4. Once that many seconds ahead are in English, the video plays, and each line appears with
   its words, not after them. If the work falls behind, the video waits again. A tap on Play
   overrides the wait.

How it works, and what it does not do:
- The page script notes the sound-only part of what the site's player gives the browser.
  Since 1.3.0 that includes players that keep sound and picture in one buffer (as
  Dailymotion does): only the sound track's samples are kept there, never the picture. It
  is passed to the app only after **Start AI subtitles**.
- **Nothing extra is downloaded** and no hidden player runs. The app reads only what the
  site has already loaded for the visible video.
- **How far ahead depends on the site.** A player that loads 30 s ahead allows at most
  about 30 s. With a longer delay, the status says "the site loads only N s ahead" and the
  video plays on instead of waiting forever.
- Where the sound can only be heard as it plays (a plain video file without Media Source
  Extensions), the app uses live subtitles as in 1.2.0, and the status says Smart Delay
  cannot wait.
- **Protected (DRM) sound is never read.** The app says so and offers the other sources.

## New: Foreign video sites directory

New tabs in the browser open a **directory of 38 official video sites** in other
languages, grouped by region: East Asia, Southeast Asia, South Asia, Europe, the Americas,
the Middle East and archives. It has a filter for name, language or country, and it is also
in the browser menu as **Foreign video sites**.
- Each entry shows the site's languages.
  - **Tested with Smart Delay** marks the two sites tried in this app (Bilibili.tv and
    Dailymotion).
  - **Often its country only** and **Sign-in for much of it** warn about common limits.
- The page is part of the app, so nothing is fetched to show it. Only the directory is
  served from that address; other app files cannot be read through it.
- Every address answered when the list was made (11 November 2026). Four candidate sites
  that refused scripted checks or did not load were left out. The other 36 sites have not
  been tried with subtitles. The app is not affiliated with any of them.

## Measured (emulator)

The local test pages play the public FLEURS test movie (257 s, 8 languages, 16 utterances)
through a small Media Source Extensions player that loads 30 s ahead, like hls.js.

| Check | Result |
|---|---|
| Separate sound buffer, Smart Delay 15 s | Video waited 13.6 s after Play (including loading the models), then played to the end without waiting again. 16 of 16 utterances had a subtitle on screen; the first line appeared a median **0.09 s after the speech started** (range 0.01–0.18 s). In 1.2.0 the live subtitles came 2.8 s late. |
| Its speed | 2.68× real time; English was a median 25 s ahead of the playhead |
| Its quality | chrF 41.0 (1.2.0 live: 41.3; the movie player's Full Movie mode: 43.6) |
| Sound and picture in one buffer (as on Dailymotion), 15 s | Waited 11.2 s, then played to the end. 16 of 16 subtitled, median 0.04 s after the speech (−0.02 to 0.18 s). chrF 37.9. The difference from 41.0 is run-to-run: Korean was cut into different pieces (18.1 against 41.2) |
| 60 s delay on a page that loads only 30 s | Played on after 15.2 s, with "the site loads only N s ahead" in the status |
| Seek to 120 s with 60 s delay | Waited with "0 of 60 s ready", dropped the old work, then played on with subtitles |
| Plain video file (no Media Source Extensions) | Live subtitles, as in 1.2.0, with the "Smart Delay cannot wait" explanation |
| Captions with 15 s | The captions were translated ahead before Play, so no wait was needed |
| Crashes, ANRs | 0 in every run |
| Unit tests | 55 pass |

Real sites, on the emulator (counts only; there is no reference translation, so the
accuracy is unmeasured):

| Site | Result |
|---|---|
| Dailymotion (a public news video, Tagalog) | Sound and picture in one buffer, read. Waited 14.1 s after Start (including loading the models), then played. English stayed 21–29 s ahead; the site loads about 30 s ahead. Language detected: Tagalog |
| Bilibili.tv (a free anime episode, Japanese) | Separate sound buffer (AAC, 48 kHz), read. Waited 12.7 s, then played. English stayed 22–26 s ahead; the site loads about 28 s ahead. Language detected: Japanese. Two other titles asked for a login and did not play |

## Permissions and data

No new permissions. The sound pieces stay in the page's and the app's memory: the last
20–30 s behind the playhead and what was loaded ahead, at most 12 MB per buffer in the
page. Nothing is saved or sent anywhere. Details are in
[permissions-and-privacy.md](permissions-and-privacy.md).

## Known limitations

- **No real phone yet.** Whether a phone keeps up, and its heat and battery use with the
  sound read ahead, are unmeasured. On the emulator the work ran 2.7–2.9× faster than real
  time with Whisper base; a slow phone may make the video wait again more often.
- **Each site decides how far ahead it loads**, usually 20–60 s. A 60 s delay is reached
  only on sites that load that much.
- Sites that keep sound and picture in one **WebM** buffer are not read ahead (live
  subtitles are used). One-buffer MP4, as on Dailymotion, is read.
- Adverts play before many videos. Smart Delay starts with the main video's sound.
- Sites that need a login, a subscription or a particular country may not play in the
  emulator or on a phone; the directory marks the common cases.
- **Google Play:** the Play bundle is `AI-Subtitle-Translator-play v1.3.0.aab`, built the same
  way as for 1.2.0 (models in an install-time asset pack). It has not been uploaded to Play.
