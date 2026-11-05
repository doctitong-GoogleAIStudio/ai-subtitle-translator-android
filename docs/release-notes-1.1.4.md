# AI Subtitle Translator 1.1.4: release notes

Developer: aivicventures, https://aivicventures.com. Package
`com.aivicventures.subtitletranslator`, versionCode 7. Signed with the same release key, so it
installs over earlier versions and keeps recent movies and saved subtitles. It also includes
everything in the [1.1.3 notes](release-notes-1.1.3.md).

## Fixed: blank subtitles while someone is speaking

- **Lines already under way when a chunk begins are kept.** Whisper dated such a line to the
  1 s of lead-in audio before the chunk, and the app threw the whole line away. A long line
  could empty a chunk: one 15.5 s line left 15 s with no subtitle. Every skip created this
  situation, so skips cost far more speech than their own length.
- **Lines appear after each Whisper run.** They used to wait for the end of the 15 s chunk, so
  the first lines of a slow chunk could arrive after they had been spoken.
- **Fewer and smaller skips.** The app now skips only when the next Whisper run would finish too
  late, and by about the time that run takes, at most one chunk. It used to estimate from a whole
  chunk, so it skipped audio even when it was keeping up.

## Measured

On the emulator with the same 6-minute film clip, not on a phone. A "long blank" is 1.5 s or
more of detected speech with no subtitle on screen.

| Long blanks | 1.1.3 | 1.1.4 |
|---|---|---|
| Normal setup (Whisper base, M2M100, 4 threads) | 5, totalling 10 s | 2, totalling 3 s |
| Slow setup (Whisper base, M2M100, 1 thread) | 6, totalling 12 s | 2, totalling 3 s |
| Slowest setup (Whisper small translating, 4 threads) | 16, totalling 59 s | 10, totalling 31 s |

| Slowest setup in detail | 1.1.3 | 1.1.4 |
|---|---|---|
| Speech with no subtitle | 38 % | 20 % |
| Subtitle lines | 53 | 68 |
| Audio skipped | about 17 s | 2 s |

The 1.1.3 log reported 56 s skipped, because it counted audio that was already covered. The
17 s is that log's figures with the covered audio taken out, and the log line is now correct.

| FLEURS test movie | 1.1.3 | 1.1.4 |
|---|---|---|
| chrF (higher is better) | 42.4 | 43.6 |
| Utterances missing | not recorded for this run | 0 of 16 |

The film clip cannot be scored, because its dialogue is not read. Its results above are timings
and counts only.

## Known limitations

- **Not confirmed on a real phone or with Chinese speech.** The film clip is Korean. The new
  build was not run through the simulated heat or memory checks in the 1.1.3 notes.
- **Repeated-line filter.** Whisper small translating can loop on one line. The app keeps the
  first and drops the rest, which leaves a blank. This caused 22 s of the 31 s still blank in the
  slowest setup. Base with M2M100 had no long blanks from it.
- **A line under way when a chunk begins may be missing its first words**, when no earlier chunk
  heard them.
- **A phone that stays below real-time speed still skips audio.**
