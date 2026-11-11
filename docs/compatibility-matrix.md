# Compatibility matrix (version 1.3.1)

What has been checked, where, and what has not. **Every "works" below is from the Android
emulator on this PC**: Android 16 (API 36), x86_64, 6 vCPU with AVX2, 4 GB RAM, WebView 134.
No real phone has run 1.2.0, 1.3.0 or 1.3.1. "Not tested" means exactly that; it is not a guess that it fails.

## Sound sources

| Source | Where | Permission | Emulator result | Limits |
|---|---|---|---|---|
| The video's captions (translated) | Browser | None | Works: Spanish test track, 0.28 s per line, shown a median 0.07 s after the caption's start | Captions served from another site without CORS cannot be read. Only tracks the page exposes to `video.textTracks` are seen. Captions drawn into the picture are not seen |
| The video's sound in the browser | Browser | None | Works: 38 of 38 lines shown, median delay 2.8 s | The first tap on the video is needed. Cross-origin video without CORS and DRM video cannot be read |
| Internal audio | Browser, floating subtitles | Record audio + screen-capture consent each session | 1.1.0 emulator: YouTube 20.10.41 and Google Photos captured. Not re-run with 1.2.0 | Android 10+. Apps that opt out of capture and DRM apps give silence |
| Microphone | Browser, floating subtitles | Record audio | Starts and listens. The emulator microphone is silent, so the no-sound notice was checked, but recognition from a room was not | Room noise, distance and volume |
| External device (big subtitles in the app) | Live screen | Record audio | 1.1.0 emulator: works with the test file | As Microphone |
| Test file (developer hook) | Live screen | — | Normal: 37 of 37 lines, median 1.6 s, chrF 34.8. Slow stand-in: 31 of 34 lines | Developer use only (`debug.subtrans.testhooks`) |

## Video sites in the browser

Two public sites were tried on the emulator with 1.3.0 (counts only, accuracy unmeasured):

| Site | Player | Smart Delay with the video's sound |
|---|---|---|
| Dailymotion (a Tagalog news video) | Sound and picture in one MP4 buffer | Works: read ahead, waited 14.1 s, then 21–29 s ahead; the site loads about 30 s |
| Bilibili.tv (a free Japanese anime episode) | Separate sound buffer, AAC 48 kHz | Works: waited 12.7 s, then 22–26 s ahead; the site loads about 28 s. Two other titles needed a login |

With 1.3.1 and a 60 s delay, Scan ahead reached it on both: Dailymotion two jumps (61 s
loaded, played 21 s after Start), Bilibili.tv two jumps (played 13 s after Start).

The other 36 sites in the browser's directory have not been tried. Otherwise, whether a
site works depends on how it serves video:

| How the site serves video | Captions | The video's sound | Internal audio / microphone |
|---|---|---|---|
| Media Source Extensions stream, sound in its own buffer or one MP4 buffer with the picture | Works, if it has text tracks | Works, and Smart Delay can wait up to what the site loads ahead (local test pages, Dailymotion, Bilibili.tv) | Expected to work |
| Media Source Extensions, sound and picture in one WebM buffer | Works, if it has text tracks | Live only (heard as it plays); Smart Delay cannot wait | Expected to work |
| Same-site video file (HTML5 `<video>`, no MSE) | Works, if it has text tracks | Works live (local test page); Smart Delay cannot wait | Expected to work |
| Video from another site that allows sharing (CORS) | Expected to work, not tested | Expected to work, not tested | Expected to work |
| Video from another site without CORS | Not readable | Refused with an explanation (tested) | Expected to work |
| DRM (Widevine) video | Depends on the site | Not readable (by design) | Silence expected; the app does not get around DRM |
| Video inside a frame (embedded player) | The page script runs in every frame; frames from other sites not tested | As above (CORS rules apply inside the frame) | Expected to work |

## Android versions and devices

| | Status |
|---|---|
| Android 16 (API 36), emulator | Tested (the emulator results above) |
| Android 8–15 | Not tested. Minimum is Android 8.0 (API 26). Internal audio needs Android 10. The per-app screen-share choice of Android 14+ is avoided by asking for the whole display |
| Phone skins (One UI, HyperOS/MIUI, ColorOS, Funtouch) | Not tested. Some limit overlays or background services; a refused floating window now ends with an explanation |
| ARM phones (arm64-v8a, armeabi-v7a) | Not tested with 1.2.0. M2M100 (captions, and live translation with base or tiny) needs 64-bit |
| WebView versions other than 134 | Not tested. The app needs `androidx.webkit` document-start scripts and web message listeners. Where the phone's WebView lacks them, the browser still browses but cannot read page audio or captions |

## Video formats in the browser

Whatever the phone's WebView plays. On the emulator, WebM (VP8/Opus) played. MPEG-4 Part 2
video in MKV did not play in WebView 134. Other formats were not tried.
