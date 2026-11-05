# AI Subtitle Translator 1.1.3: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 6. Signed with the same release key, so it
installs over 1.0 and 1.1 and keeps recent movies and saved subtitles.

## Fixed: subtitles fading out after about 5 minutes on a hot phone

A phone showed "Translation delayed, 0 s ahead, saving power: phone is hot" at 6:16 of a film,
with a speed reading of 2.1x. Heat was the trigger, and the speed reading hid the delay.

- **Less work all the time.** Speech separated by up to 3 s now shares one Whisper run. Each
  run costs a full encoder pass.
- **Less work when warm.** From Android's "moderate" heat level, before the phone reaches
  "severe", runs share speech up to 6 s apart and unsure lines are not decoded a second time.
- **An honest speed reading.** The speed shown and used now counts only chunks with speech.
- **Late jobs start further ahead.** When a chunk's lines would come too late, the app works on
  the audio the viewer will reach next, instead of lines that would never be seen. Skips under
  2 s are not made.
- **Short audio as the last step.** Below 1x speed, Whisper uses a shorter audio window until the
  speed passes 2x.

## Measured

On the emulator, not on a phone.

| Up to 3 s merged (film clip, 6 minutes) | Before | After |
|---|---|---|
| Whisper runs | 34 | 23 |
| Recognition time | 45 s | 35 s |
| Subtitle lines | 69 | 72 |

| FLEURS test movie, chrF | Before | After |
|---|---|---|
| Merged up to 3 s (now always on) | 42.3 | 42.4 |
| Merged up to 6 s (only when warm) | 42.3 | 37.7 |
| Short audio (only below 1x) | 42.1 | 32.4 |

The FLEURS movie changes language every sentence, the worst case for merging. The film clip
cannot be scored, because its dialogue is not read.

| Other checks on the 6-minute film clip | Result |
|---|---|
| Simulated heat at normal speed, on a build just before the last two fixes | All audio covered, no skips, Whisper runs 44 to 23, recognition 74 s to 55 s |
| Deliberately slow setup (one thread, Whisper small, M2M100) | 86 % of the audio covered up to 228 s, against 75 % for the first attempt at this fix. Android then ended the app for lack of memory, as described below |
| Memory over 7 minutes (Whisper base, M2M100, short audio on) | Steady at about 0.9 GB, all audio covered, no sign of a leak |

## Known limitations

- **Not confirmed on a real phone.** The emulator does not heat up. Heat was simulated with
  Android's thermal override, which shows that the app reacts, not how hot a phone gets.
- **A phone can still get hot.** This puts off the slowdown and softens it; it may not prevent it.
- **Very slow or very hot phones still skip audio**, and short audio lowers quality.
- **Memory.** Whisper small with M2M100 together, a pairing only a test can force, takes about
  1.1 GB. Android ended the app at that size on a 4 GB emulator, in three slow-setup runs out of
  the last four.
- **Movie files without a seek index read slower the further in they are** (about 2 ms more per
  second of movie on the emulator). Not fixed.
