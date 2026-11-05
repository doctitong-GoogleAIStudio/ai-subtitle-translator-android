# Real-phone test plan

Phase 3 added fast-core pinning, a default thread count, a battery saver and FFmpeg
decoders. On the emulator these were shown to take effect, but the emulator has identical
cores, no real battery and no heat, so whether they *help* is still unmeasured. This plan
measures that on real phones and says which setting each result decides.

Everything runs from the PC with `scripts/device_bench.sh`. It processes the FLEURS test
movies in Full Movie mode and records speed, memory, battery temperature, thermal status,
Android's per-app battery estimate and subtitle quality (chrF). It then writes
`bench/<phone>-<date>/results.md`.

## Phones

In order of value:

1. **Your own phone**, whatever it is.
2. **A mid-range phone with 2 big + 6 little cores** (Helio G99, Snapdragon 6- or 7-series,
   Exynos 1280/1380). This is where keeping the work off the little cores should matter
   most, and where clock speed alone cannot tell the cores apart.
3. **An older or 4 GB phone**, ideally a 32-bit (armeabi-v7a) one. It shows the memory
   limits and the Whisper-only path, because M2M100 needs a 64-bit phone.

One phone is enough to start. Each extra phone class makes the defaults safer.

## Before you start

- **On the phone:**
  1. Turn on Developer options (tap Build number 7 times), then turn on **USB debugging**.
  2. Connect the phone by USB and accept the "Allow USB debugging" prompt.
  3. Battery at 50 % or more. The phone at room temperature, out of its case, lying on a
     table, not in a hand or on a pillow.
  4. Close other apps and turn off battery-optimisation apps or "game boosters".
- **Install the APK:**

  ```bash
  adb install -r "AI-Subtitle-Translator-universal v1.1.4.apk"
  ```

- **In the app:**
  1. Open the Model Manager and download **Whisper small**. Download Whisper base too if you
     want the second model compared.
  2. Leave Settings at their defaults.
- **Copy the test movies:**
  1. Run `bash scripts/device_bench.sh quick`. It copies the three FLEURS test movies to
     `Movies/SubtitleBench` on the phone, then stops and names them.
  2. In the app, tap **Open a video…** and pick each one once, so the app may read it. You
     can back out right away.
  3. Run the command again.
- **What goes on the phone:** only the FLEURS test movies (public FLEURS speech, about
  70 MB). The Colony film and its excerpt never go to the phone. Results stay in `bench/`
  on the PC, which is git-ignored.

## Suites

Run them in this order. The order matters because heat builds up. The script waits for
the battery to cool to within 1 °C of its starting temperature before each run, except in
`sustained`. Each run processes 4.3 minutes of audio (3.6 for movie 2) and gives up after
40 minutes.

| Suite | Runs | Measures | Decides |
|---|---|---|---|
| `info` | 0 | Phone, SoC, RAM, each core's capacity and top frequency, and which cores the app treats as fast. | Whether fast-core detection is right for this SoC. |
| `quick` | 2 | Default settings on both FLEURS movies. | Whether the default model keeps up with playback (speed above 1×). It is also the quality baseline. |
| `threads` | up to 9 | 1, 2, 4, 6 and 8 threads, unpinned. The same counts pinned to the fast cores, where the phone has slow cores. | The default thread count, and whether **Fast cores only** stays on by default. |
| `saver` | 2 | Battery saver off and on. | Whether halving the threads saves enough battery and heat for what it costs in speed. |
| `decoders` | 4 | The multitrack movie: silent AAC decoy, AC-3, E-AC-3, DTS 5.1. | Whether the phone's own decoders or FFmpeg get used. AC-3/DTS quality should be close to AAC, and the decoy should give 0 lines. |
| `sustained` | as many as fit in 20 min | Back-to-back runs with no cool-down. | How much the phone slows when hot, and whether the app's heat reaction (half threads at SEVERE, 1 thread at CRITICAL) triggers and helps. |

Commands:

```bash
bash scripts/device_bench.sh info
```

```bash
bash scripts/device_bench.sh all
```

Or run one suite at a time, like `bash scripts/device_bench.sh threads`. Runs go into the
same folder on the same day, and `results.md` is rebuilt from every run in that folder. To
change the settings:

- `MODEL=whisper-base-q5_1` compares the smaller model.
- `ANDROID_SERIAL=<id>` picks a phone when more than one is connected.
- `SUSTAIN_MIN=30` makes the heat test longer.

## Reading the results

`results.md` has one row per run. The columns that matter:

- **`speed_x`:** audio seconds processed per second of work. Above 1.0 means the
  subtitles keep ahead of playback. `slowest_chunk_x` shows the worst moment.
- **`threads_used` and `pin`:** what the engine actually ran with. A `power_changes` entry
  means the battery saver or heat reaction stepped in.
- **`peak_pss_mb`:** the app's memory at its highest point.
- **`temp_peak_c` and `thermal_max`:**
  - `temp_peak_c` is the battery temperature.
  - `thermal_max` is Android's thermal status: 0 none, 1 light, 2 moderate, 3 severe,
    4 critical.
- **`mah`:** Android's estimate of the battery the app used. The phone is told it is
  unplugged during each run so this is counted, but it stays an estimate from the phone's
  power profile, not a measured drain.
- **`chrF` and `missing`:** subtitle quality against the FLEURS references. Emulator
  results with Whisper small:
  - Movie 1 scored 46.7.
  - AC-3 scored 45.6, E-AC-3 47.2 and DTS 45.4.

## Decisions

The results feed these code defaults. Each rule is written so that a single phone's numbers
can be applied without arguing over noise.

1. **Default thread count** (`CpuTopology.recommendedThreads`, now: number of fast cores,
   capped at 4).
   - The best count is the lowest thread count whose `speed_x` is within 5 % of the
     fastest run.
   - If that differs from the current default on two phone classes, change the formula.
   - If only one phone disagrees, note it in the README.
2. **Fast cores only, on by default.**
   - Compare `threadsN-fast` with `threadsN-all` at the same N.
   - Keep the setting on by default if pinned is at least as fast, within 3 %, and uses no
     more `mah`.
   - If pinned is clearly slower on a 2 + 6 phone, turn the default off, or pin only when
     there are at least 4 fast cores.
3. **Fast-core detection** (`CAPACITY_SHARE` 0.5, `FREQUENCY_SHARE` 0.8).
   - The `fast` list in `device.txt` must match the SoC's published big and prime cores.
   - If it doesn't, adjust the share and add the phone's values to `DeviceTest`.
4. **Battery saver** (now: half the threads).
   - Worth keeping if `saver-on` uses at least 25 % less `mah` per audio minute, or peaks
     at least 2 °C cooler.
   - If it also drops Whisper small below 1×, the saver should switch the model to base as
     well, and say so in the status line.
5. **Heat reaction** (half threads at status 3, one thread and a 10 s pause at 4).
   - In `sustained`, compare the last run's `speed_x` with the first's.
   - If speed falls by more than 30 % with no `power_changes`, the phone throttled before
     Android reported SEVERE, so consider reacting at MODERATE (2).
   - If the phone reached status 4, check that one thread brought it back down.
6. **Decoders.**
   - Note which decoder each track used. Many phones decode AC-3 and E-AC-3 themselves,
     but few decode DTS.
   - chrF should be within 2 points of `quick-movie1`, and `track0-silent` should give
     0 lines.
   - Playback should not stutter. Watch the first seconds of a DTS track on the phone
     while `decoders` runs.
7. **Memory.**
   - If `peak_pss_mb` goes above about a third of the phone's RAM, low-memory phones risk
     the app being killed in the background.
   - In that case, recommend base instead of small on phones of that size.
8. **Quality must not move with tuning.**
   - Threads and pinning change only the speed. chrF across the `threads` runs should
     stay within ±1.5.
   - A bigger spread means a bug, not tuning.

## Afterwards

- The script undoes the "unplugged" setting after each run and if it is stopped.
- To remove the test movies from the phone:

  ```bash
  adb shell rm -r /sdcard/Movies/SubtitleBench
  ```

- Copy the `results.md` table into the README's results section, with the phone and SoC
  name, then apply the decisions above as one change with its unit tests.

## Optional: real battery drain

With the USB cable plugged in, the phone charges, so only Android's estimate is available.
For a measured drain, use Wireless debugging (Android 11 and later: Developer options →
Wireless debugging → Pair device with pairing code). Steps:

1. Pair the phone with the address and code from "Pair device with pairing code":

   ```bash
   adb pair <ip>:<pairing-port>
   ```

2. Connect to the address shown on the Wireless debugging screen itself:

   ```bash
   adb connect <ip>:<port>
   ```

3. Unplug the cable.
4. Run the `saver` suite with `ANDROID_SERIAL=<ip>:<port>`.
5. Compare the battery `level` in `device.txt` before and after. Run `info` again to
   record the "after" level.
