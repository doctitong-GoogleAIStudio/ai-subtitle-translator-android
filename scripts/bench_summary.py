"""Summarise a scripts/device_bench.sh output folder into results.md and results.csv.

For each run: wall time, processing speed (audio seconds per second of decode + ASR + MT,
from the engine's chunk lines), threads and pinned cores, decoders, peak memory, battery
temperature, thermal status, estimated battery use, power-saving changes and the chrF score
against the FLEURS references (same rule as scripts/score_e2e.py).

    python -I scripts/bench_summary.py <out-dir>
"""

from __future__ import annotations

import csv
import importlib.util
import json
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts
_spec = importlib.util.spec_from_file_location("score_e2e", Path(__file__).with_name("score_e2e.py"))
score_e2e = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(score_e2e)

TEST_MEDIA = Path(__file__).resolve().parent.parent / "test-media"
CHUNK = re.compile(r"chunk \S+ audio=([\d.]+)s decode=([\d.]+)s asr=([\d.]+)s mt=([\d.]+)s speed=([\d.]+)x .*threads=(\d+)")


def score(truth_name: str, log: str) -> tuple[float, int]:
    truth = json.load(open(TEST_MEDIA / truth_name, encoding="utf-8"))
    subs = score_e2e.parse_logcat(log)
    hyps, refs, missing = [], [], 0
    for tr in truth:
        hyp = " ".join(t for s, t in subs if tr["start"] - 1.0 <= s < tr["end"] + 0.5).strip()
        hyps.append(hyp)
        refs.append(tr["english"])
        missing += not hyp
    return score_e2e.chrf(hyps, refs), missing


def summarise(run_dir: Path, meta_path: Path) -> dict:
    meta = dict(line.split("=", 1) for line in meta_path.read_text(encoding="utf-8").splitlines() if "=" in line)
    name = meta["name"]
    log = (run_dir / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    chunks = [tuple(map(float, m.groups())) for m in CHUNK.finditer(log)]
    audio = sum(c[0] for c in chunks)
    proc = sum(c[1] + c[2] + c[3] for c in chunks)
    opened = re.search(r"open .* threads=(\d+) .*decoder=(\S+) track=(\S+) pin=(\S+)", log)
    playback = re.search(r"playback audio decoder (\S+)", log)
    power = re.findall(r"power: saving=(\S+) critical=(\S+) reason=(.*?) threads=(\d+)", log)
    samples = [line.split() for line in (run_dir / f"{name}.samples").read_text().splitlines()]
    samples = [list(map(int, s)) for s in samples if len(s) == 4 and all(x.lstrip("-").isdigit() for x in s)]
    known = lambda i: [s[i] for s in samples if s[i] >= 0]  # -1 = the phone did not report it
    row = {
        "run": name,
        "status": meta["status"],
        "wall_s": int(meta["wall"]),
        "audio_s": round(audio, 1),
        "speed_x": round(audio / proc, 2) if proc else "",
        "slowest_chunk_x": round(min(c[4] for c in chunks), 2) if chunks else "",
        "threads": opened.group(1) if opened else "",
        "threads_used": "/".join(sorted({str(int(c[5])) for c in chunks}, key=int)),
        "pin": opened.group(4) if opened else "",
        "decoder": opened.group(2) if opened else "",
        "playback_decoder": playback.group(1) if playback else "",
        "peak_pss_mb": round(max(known(1)) / 1024) if known(1) else "",
        "temp_start_c": int(meta["temp_start"]) / 10 if meta.get("temp_start", "").isdigit() else "",
        "temp_peak_c": max(known(2)) / 10 if known(2) else "",
        "thermal_max": max(known(3)) if known(3) else "",
        "mah": meta.get("mah", ""),
        "power_changes": "; ".join(f"{r or '-'}→{t} threads" for _, _, r, t in power if r != "-"),
        "lines": len(score_e2e.parse_logcat(log)),
        "chrF": "",
        "missing": "",
    }
    if meta.get("truth", "-") not in ("-", "") and chunks:
        chrf, missing = score(meta["truth"], log)
        row["chrF"], row["missing"] = round(chrf, 1), missing
    return row


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    run_dir = out / "runs"
    metas = sorted(run_dir.glob("*.meta"), key=lambda p: p.stat().st_mtime)
    rows = [summarise(run_dir, m) for m in metas]
    if not rows:
        sys.exit(f"no runs in {run_dir}")
    with open(out / "results.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    cols = ["run", "status", "wall_s", "speed_x", "slowest_chunk_x", "threads_used", "pin", "decoder",
            "playback_decoder", "peak_pss_mb", "temp_peak_c", "thermal_max", "mah", "chrF", "missing", "power_changes"]
    device = (out / "device.txt").read_text(encoding="utf-8") if (out / "device.txt").exists() else ""
    md = ["# Device benchmark", "", "```", device.strip(), "```", "",
          "| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    md += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for r in rows]
    md += ["", "speed_x = audio seconds processed per second of decode + recognition + translation "
           "(above 1 keeps ahead of playback). mah is Android's per-app estimate while the phone "
           "was told it was unplugged."]
    (out / "results.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))


if __name__ == "__main__":
    main()
