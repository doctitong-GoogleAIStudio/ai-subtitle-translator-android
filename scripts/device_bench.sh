#!/bin/bash
# Benchmarks the app on a phone connected over adb: speed, memory, heat and estimated
# battery use of Full Movie processing on the FLEURS test movies, for the Phase 3 settings
# (fast-core pinning, thread count, battery saver) and the AC-3/E-AC-3/DTS decoders.
# See docs/real-device-test-plan.md.
#
#   scripts/device_bench.sh <suite> [out-dir]
#
# Suites: info, quick, threads, saver, sustained, decoders, all (= every suite in that order).
# Results go to out-dir (default bench/<phone model>-<date>, git-ignored): one log, sample
# and meta file per run in runs/, device.txt, and results.md / results.csv from
# scripts/bench_summary.py.
#
# Environment: ANDROID_SERIAL picks the device; MODEL (default whisper-small-q5_1);
# TRANSLATOR (auto); TIMEOUT per run in s (2400); COOL_C, the cool-down margin above the
# starting battery temperature in °C (1.0); SUSTAIN_MIN (20); PYTHON for the summary.
#
# Needs: the release APK installed, MODEL downloaded in the app's Model Manager, and each test
# movie picked once in the app ("Open a video…") so the app may read it; the script pushes
# the movies and names any that still need picking. Only the FLEURS test movies are used.
# While a run lasts, the phone is told it is unplugged (dumpsys battery unplug) so battery
# stats count; this is undone after each run and on exit.
set -uo pipefail
export MSYS_NO_PATHCONV=1

SUITE="${1:?usage: device_bench.sh info|quick|threads|saver|sustained|decoders|all [out-dir]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.aivicventures.subtitletranslator
MODEL="${MODEL:-whisper-small-q5_1}"
TRANSLATOR="${TRANSLATOR:-auto}"
TIMEOUT="${TIMEOUT:-2400}"
COOL_C="${COOL_C:-1.0}"
SUSTAIN_MIN="${SUSTAIN_MIN:-20}"
REMOTE=/sdcard/Movies/SubtitleBench
MOVIES="fleurs_e2e.mkv fleurs_e2e2.mkv fleurs_multitrack.mkv"

ADB="${ADB:-$(command -v adb || true)}"
for c in "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" "${ANDROID_HOME:-}/platform-tools/adb"; do
  [ -n "$ADB" ] || { [ -x "$c" ] && ADB="$c"; }
done
[ -n "$ADB" ] || { echo "adb not found; set ADB"; exit 1; }
a() { "$ADB" "$@" | tr -d '\r'; }
sh_() { a shell "$@"; }

[ "$(a get-state 2>/dev/null)" = device ] || { echo "no device (set ANDROID_SERIAL if several)"; exit 1; }
sh_ pm path "$PKG" | grep -q package: || { echo "$PKG is not installed"; exit 1; }
PHONE="$(sh_ getprop ro.product.model | tr -c 'A-Za-z0-9\n' '_')"
OUT="${2:-$ROOT/bench/$PHONE-$(date +%Y%m%d)}"
mkdir -p "$OUT/runs"
trap 'sh_ dumpsys battery reset >/dev/null 2>&1; sh_ am force-stop $PKG >/dev/null 2>&1' EXIT

battery_temp() { sh_ dumpsys battery | sed -n 's/^ *temperature: *//p' | head -1; }  # tenths of °C
thermal() { sh_ dumpsys thermalservice | sed -n 's/^Thermal Status: *//p' | head -1; }
pss_kb() { sh_ dumpsys meminfo $PKG | awk '/TOTAL PSS/ {print $3; exit}'; }

# CPU layout as the app sees it: capacity when more than one core reports it, else top
# frequency; fast cores reach 50 % of the top capacity or 80 % of the top frequency.
CORES="$(sh_ ls /sys/devices/system/cpu | grep -cE '^cpu[0-9]+$')"
fast_cores() {
  local file share
  if [ "$(sh_ "cat /sys/devices/system/cpu/cpu*/cpu_capacity 2>/dev/null" | grep -c .)" -gt 1 ]; then
    file=cpu_capacity; share=0.5
  else
    file=cpufreq/cpuinfo_max_freq; share=0.8
  fi
  for ((i = 0; i < CORES; i++)); do
    echo "$i $(sh_ cat /sys/devices/system/cpu/cpu$i/$file 2>/dev/null)"
  done | awk -v s=$share '$2 != "" {v[$1] = $2; if ($2 > top) top = $2}
    END {for (i in v) if (v[i] >= top * s) print i}' | sort -n | paste -sd, -
}
FAST="$(fast_cores)"
ALL="0-$((CORES - 1))"
NFAST=$(awk -F, '{print NF}' <<<"$FAST")
HETERO=$([ "$NFAST" -lt "$CORES" ] && echo 1 || echo 0)

device_info() {
  {
    echo "model: $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)"
    echo "soc: $(sh_ getprop ro.soc.manufacturer) $(sh_ getprop ro.soc.model) ($(sh_ getprop ro.board.platform))"
    echo "android: $(sh_ getprop ro.build.version.release) (API $(sh_ getprop ro.build.version.sdk))"
    echo "abi: $(sh_ getprop ro.product.cpu.abilist)"
    echo "ram: $(sh_ cat /proc/meminfo | awk '/MemTotal/ {printf "%.1f GB", $2 / 1048576}')"
    echo "app: $(sh_ dumpsys package $PKG | sed -n 's/^ *versionName=//p' | head -1)"
    echo "cores: $CORES  fast: $FAST  heterogeneous: $HETERO"
    for ((i = 0; i < CORES; i++)); do
      echo "  cpu$i capacity=$(sh_ cat /sys/devices/system/cpu/cpu$i/cpu_capacity 2>/dev/null || echo -)" \
        "max_khz=$(sh_ cat /sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq 2>/dev/null || echo -)"
    done
    echo "battery: $(sh_ dumpsys battery | sed -n 's/^ *level: *//p') %  temperature: $(battery_temp | awk '{printf "%.1f", $1 / 10}') °C  thermal status: $(thermal)"
    echo "power saver: $(sh_ settings get global low_power)"
  } | tee "$OUT/device.txt"
}

declare -A VID
find_movies() {
  local f id missing=""
  for f in $MOVIES; do
    id="$(sh_ "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$f'\"" |
      sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)"
    if [ -z "$id" ]; then
      [ -f "$ROOT/test-media/$f" ] || { echo "test-media/$f is missing on this PC"; exit 1; }
      echo "pushing $f"
      a push "$ROOT/test-media/$f" "$REMOTE/$f" >/dev/null
      sh_ "content call --method scan_volume --uri content://media --arg external_primary" >/dev/null 2>&1
      sh_ am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$REMOTE/$f" >/dev/null 2>&1
      sleep 3
      id="$(sh_ "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$f'\"" |
        sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)"
    fi
    [ -n "$id" ] || { echo "the phone's media store does not list $f yet; open it once in the app and re-run"; exit 1; }
    VID[$f]=$id
    sh_ dumpsys activity permissions | grep -q "document/video%3A$id " || missing="$missing $f"
  done
  if [ -n "$missing" ]; then
    echo "In the app, tap \"Open a video…\" and pick each of these once (Movies folder), then re-run:$missing"
    exit 2
  fi
}

cool_down() { # waits until the battery is back near its starting temperature (10 min at most)
  local limit=$(awk -v b="$BASE_T" -v m="$COOL_C" 'BEGIN {print b + m * 10}') t waited=0
  while t="$(battery_temp)"; [ "${t:-0}" -gt "$limit" ] && [ $waited -lt 600 ]; do
    [ $waited = 0 ] && echo "  cooling down from $((t / 10)).$((t % 10)) °C"
    sleep 20; waited=$((waited + 20))
  done
}

APP_UID="$(sh_ dumpsys package $PKG | sed -n 's/^ *appId=//p' | head -1)"
app_mah() { # estimated mAh used by the app since the last batterystats reset
  sh_ dumpsys batterystats --charged $PKG | awk -v u="u0a$((APP_UID - 10000)):" '$1 == "UID" && $2 == u {print $3; exit}'
}

# run <name> <movie> <truth-file|-> [am extras...]
run() {
  local name=$1 movie=$2 truth=$3; shift 3
  local log="$OUT/runs/$name.log" samples="$OUT/runs/$name.samples" status=TIMEOUT
  [ "${NOCOOL:-}" = 1 ] || cool_down
  sh_ am force-stop $PKG; a logcat -c; a logcat -G 16M >/dev/null 2>&1
  sh_ dumpsys battery unplug; sh_ dumpsys batterystats --reset >/dev/null
  local t_start; t_start="$(battery_temp)"
  local t0; t0=$(date +%s)
  sh_ am start -n $PKG/.ui.PlayerActivity \
    -d "content://com.android.providers.media.documents/document/video%3A${VID[$movie]}" \
    --es mode FULL --es model "$MODEL" --es translator "$TRANSLATOR" \
    --ez from_start true --ez clear_cache true "$@" >/dev/null
  : > "$samples"
  while true; do
    sleep 10
    local pss temp th; pss="$(pss_kb)"; temp="$(battery_temp)"; th="$(thermal)"
    echo "$(($(date +%s) - t0)) ${pss:--1} ${temp:--1} ${th:--1}" >> "$samples"
    a logcat -d -s SubEngine:I SubPlayer:I WhisperAsr:I CpuTopology:I FfmpegAudio:I M2M100:I AndroidRuntime:E > "$log"
    grep -q "status COMPLETE" "$log" && { status=COMPLETE; break; }
    grep -q "FATAL EXCEPTION" "$log" && { status=CRASH; break; }
    a logcat -d | grep -q "Permission Denial.*video%3A${VID[$movie]}" && { status=NO_ACCESS; break; }
    [ $(($(date +%s) - t0)) -ge "$TIMEOUT" ] && break
  done
  local wall=$(($(date +%s) - t0)) mah; mah="$(app_mah)"
  sh_ am force-stop $PKG; sh_ dumpsys battery reset
  printf 'name=%s\nmovie=%s\ntruth=%s\nstatus=%s\nwall=%s\nmah=%s\ntemp_start=%s\nextras=%s\n' \
    "$name" "$movie" "$truth" "$status" "$wall" "${mah:-}" "$t_start" "$*" > "$OUT/runs/$name.meta"
  echo "  $name: $status in ${wall}s, ${mah:-?} mAh"
}

# References in test-media/, by file name (bench_summary.py looks them up there).
TRUTH1=fleurs_e2e_truth.json
TRUTH2=fleurs_e2e2_truth.json

suite_quick() { # default settings on both FLEURS movies
  echo "== quick"
  run quick-movie1 fleurs_e2e.mkv "$TRUTH1"
  run quick-movie2 fleurs_e2e2.mkv "$TRUTH2"
}

suite_threads() { # thread count, unpinned and on the fast cores
  echo "== threads"
  local t
  for t in 1 2 4 6 8; do
    [ $t -le "$CORES" ] || continue
    run "threads$t-all" fleurs_e2e.mkv "$TRUTH1" --ei threads $t --es pin_cpus "$ALL"
    if [ "$HETERO" = 1 ] && [ $t -le $((NFAST + 2)) ]; then
      run "threads$t-fast" fleurs_e2e.mkv "$TRUTH1" --ei threads $t --es pin_cpus "$FAST"
    fi
  done
  [ "$HETERO" = 1 ] || echo "  all cores are alike: pinning has nothing to choose, only unpinned runs"
}

suite_saver() { # battery saver off and on, other settings default
  echo "== saver"
  run saver-off fleurs_e2e.mkv "$TRUTH1" --ez battery_saver false
  run saver-on fleurs_e2e.mkv "$TRUTH1" --ez battery_saver true
}

suite_sustained() { # back-to-back runs without cooling down: does heat slow it down?
  echo "== sustained ($SUSTAIN_MIN min)"
  cool_down
  local end=$(($(date +%s) + SUSTAIN_MIN * 60)) i=1
  while [ "$(date +%s)" -lt $end ]; do
    NOCOOL=1 run "sustained$i" fleurs_e2e.mkv "$TRUTH1"
    i=$((i + 1))
  done
}

suite_decoders() { # the multitrack movie: silent AAC decoy, then AC-3, E-AC-3 and DTS 5.1
  echo "== decoders"
  run track0-silent fleurs_multitrack.mkv - --ei audio_track 0
  run track1-ac3 fleurs_multitrack.mkv "$TRUTH1" --ei audio_track 1
  run track2-eac3 fleurs_multitrack.mkv "$TRUTH1" --ei audio_track 2
  run track3-dts fleurs_multitrack.mkv "$TRUTH1" --ei audio_track 3
}

device_info
[ "$SUITE" = info ] && exit 0
find_movies
BASE_T="$(battery_temp)"
case "$SUITE" in
  quick|threads|saver|sustained|decoders) "suite_$SUITE";;
  all) for s in quick threads saver decoders sustained; do "suite_$s"; done;;
  *) echo "unknown suite $SUITE"; exit 1;;
esac
PY="${PYTHON:-$(command -v python || command -v python3)}"
native() { command -v cygpath >/dev/null && cygpath -w "$1" || echo "$1"; }  # Windows Python needs C:\ paths
"$PY" -I "$(native "$ROOT/scripts/bench_summary.py")" "$(native "$OUT")"
