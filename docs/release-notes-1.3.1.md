# AI Subtitle Translator 1.3.1: release notes

Developer: aivicventures. Package `com.aivicventures.subtitletranslator`, versionCode 10.
Installs over 1.0 to 1.3.0 (same key). It changes only Smart Delay in the built-in browser;
everything else is as in [1.3.0](release-notes-1.3.0.md).

**Tested on the Android emulator only, not on a phone.**

## Fixed: the video stopped again every few seconds

Reported on a phone: with any Smart Delay from 5 to 60 s, the movie stopped within 5 to 10
seconds. Two causes were found and reproduced on the emulator:

1. **Pressing Play while the video waited** let it play for only 10 seconds; if the English
   was not ready by then, it stopped again, and again after each Play. On the emulator with
   a slowed-down CPU this gave 6 stops in 90 s.
   **Now:** the viewer's Play is respected. The video plays on without waiting until the
   viewer jumps elsewhere in the video, chooses the delay again or restarts subtitles. The
   status says "playing without waiting (you pressed Play …)". Same test: 1 stop, then it
   played through.
2. **The wait was not visible in full screen.** Only the status line under the page said
   why the video stopped. **Now** the waiting message is shown on the video itself, also in
   full screen: "Preparing English subtitles… 22 of 60 s".

Also new: **a limit on waiting.** If waiting brings nothing new for 45 s (no more English
ready and no more sound loaded), the video plays on and the status says the subtitles are
too slow here to wait for. Until 1.3.0 a phone too slow for the chosen delay could wait for
minutes.

## New: Scan ahead, for delays longer than the site loads

A site's player loads the sound only so far ahead, about 30 s on Dailymotion and
Bilibili.tv, so in 1.3.0 a 45 or 60 s delay could not be reached there. Now, while the video
waits:
1. Once the site's player stops loading, the app moves the same, visible video forward to the
   end of what is loaded. The video is muted and its picture hidden, and the waiting message
   stays on screen.
2. The site's own player loads on from there. The app reads that sound as before, at full
   quality, because it reads the sound data, not what comes out of the speaker.
3. When enough is loaded, or a jump brings nothing more, the video goes back to where it
   waited. It plays at normal speed once the chosen delay is in English.

So the sound is taken in faster than real time, and the movie then plays at normal speed
with subtitles made in advance.

What it does not do: the app fetches nothing itself, saves nothing, and runs no hidden
player. The viewer's own Play during a scan ends it at once and plays from where the video
waited. Protected (DRM) videos and live streams are not scanned.

After the video starts, the extra cushion is used up down to what the site normally loads
(about 30 s on these sites), because players load only that far ahead while playing.

### Measured (emulator, 4 GB, counts only)

| Check | Result |
|---|---|
| Local test page (loads 30 s), 60 s delay | One jump loaded sound to 64 s. The video went back to 0:00 and played 17–19 s after Play, with 60 s in English; subtitles on time |
| Dailymotion (Tagalog news video), 60 s delay | Two jumps loaded 61 s. Back to 37.9 s, played 21 s after Start (models loaded in that time) |
| Bilibili.tv (Japanese anime episode), 60 s delay | Two jumps loaded 51 s and more. Back to 28.7 s, played 13 s after Start, then played on (90 time reports in 90 s) |
| Viewer presses Play during the wait (CPU slowed with 2 busy loops) | 1 stop, then no more; 1.3.0: a stop every ~16 s |
| Crashes | 0 |
| Unit tests | 55 pass. The new waiting rules are in the browser screen and were checked on the emulator only; there is no unit test for them |

## Known limitations

- **Real phones are unmeasured.** On the emulator the first 12 s of sound took 62 s when the
  CPU was busy with other work (busy loops), so a slow or busy phone may hit the 45 s limit
  and play on without the full delay.
- Some sites may react to the jumps: an advert, a quality change or a spinner. If a jump
  brings no new sound (a player that loads only while playing), the scan stops and the
  video plays with what there is.
- The site player's position bar shows the jumps for a moment (the picture is hidden).
