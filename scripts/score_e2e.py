"""Score the app's subtitles for the FLEURS test movie against its reference translations.

Same rule as the Windows app's scripts/e2e_quality.py: the English subtitles that start
inside each utterance (start - 1.0 s .. end + 0.5 s) are joined and scored with chrF
(character 6-grams, beta 2, whitespace ignored; corpus level, like sacrebleu's default).

Input: an exported SRT file, or a logcat dump with the engine's "  0:00:04.6 → ..." lines.

    python scripts/score_e2e.py truth.json subtitles.srt|logcat.txt
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter


def parse_srt(text: str) -> list[tuple[float, str]]:
    out = []
    for block in re.split(r"\n\s*\n", text.strip()):
        lines = block.strip().splitlines()
        if len(lines) < 3:
            continue
        m = re.match(r"(\d+):(\d+):(\d+),(\d+)", lines[1])
        if m:
            h, mi, s, ms = map(int, m.groups())
            out.append((h * 3600 + mi * 60 + s + ms / 1000, " ".join(lines[2:])))
    return out


def parse_logcat(text: str) -> list[tuple[float, str]]:
    out = []
    for line in text.splitlines():
        m = re.search(r"SubEngine:\s+(\d+):(\d+):(\d+)\.(\d) → \S+\s+(.*)$", line)
        if m:
            h, mi, s, d = map(int, m.groups()[:4])
            out.append((h * 3600 + mi * 60 + s + d / 10, m.group(5).strip()))
    return out


def chrf(hyps: list[str], refs: list[str], order: int = 6, beta: float = 2.0) -> float:
    tot = [[0, 0, 0] for _ in range(order)]  # matches, hyp count, ref count per n
    for h, r in zip(hyps, refs):
        h, r = h.replace(" ", ""), r.replace(" ", "")
        for n in range(1, order + 1):
            hc = Counter(h[i : i + n] for i in range(len(h) - n + 1))
            rc = Counter(r[i : i + n] for i in range(len(r) - n + 1))
            tot[n - 1][0] += sum((hc & rc).values())
            tot[n - 1][1] += sum(hc.values())
            tot[n - 1][2] += sum(rc.values())
    p = sum(m / hcount if hcount else 0 for m, hcount, _ in tot) / order
    rr = sum(m / rcount if rcount else 0 for m, _, rcount in tot) / order
    if p + rr == 0:
        return 0.0
    return 100 * (1 + beta**2) * p * rr / (beta**2 * p + rr)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    truth = json.load(open(sys.argv[1], encoding="utf-8"))
    text = open(sys.argv[2], encoding="utf-8", errors="replace").read()
    subs = parse_srt(text) if sys.argv[2].lower().endswith(".srt") else parse_logcat(text)
    hyps, refs, missing = [], [], 0
    for tr in truth:
        inside = [t for s, t in subs if tr["start"] - 1.0 <= s < tr["end"] + 0.5]
        hyp = " ".join(inside).strip()
        hyps.append(hyp)
        refs.append(tr["english"])
        missing += not hyp
        print(f"[{tr['lang']}] {tr['start']:7.1f}  REF: {tr['english']}\n{'':12}HYP: {hyp or '(missing)'}")
    outside = [t for s, t in subs if not any(tr["start"] - 1.0 <= s < tr["end"] + 0.5 for tr in truth)]
    print(f"\nlines: {len(subs)}  utterances: {len(truth)}  missing: {missing}  outside speech: {len(outside)}")
    for t in outside:
        print("  outside:", t)
    print(f"chrF: {chrf(hyps, refs):.1f}")


if __name__ == "__main__":
    main()
