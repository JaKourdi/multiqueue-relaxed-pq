#!/usr/bin/env python3
# Average the bench CSV across a few server runs into one bench_results.csv.
#
#   python3 average_runs.py res/run1.out res/run2.out res/run3.out > bench_results.csv
#
# Each input is a raw MppRunner capture (banners plus the ===CSV_BEGIN===/===CSV_END===
# block). One file can also contain several concatenated runs (several CSV blocks);
# those get pooled too. I average the value columns across every run keyed by the
# experiment identity, so if the server was momentarily loaded on one run it gets
# smoothed out. Same "report the average runtime" idea HW3 uses.
#
# The rank columns are already internally averaged over SEQ_REPS and don't depend on
# the machine, so averaging them again is basically a no-op. throughput and lockFail
# are the ones that actually benefit from this.
import sys
import io
import pandas as pd

# columns that identify one experiment row; must match across runs to be averaged together
KEY = ["experiment", "strategy", "workload", "d", "c", "threads", "syncPeriod", "ringSize"]
# columns I average, with the output format matching MppRunner's own printf
VAL = {
    "throughput_ops": "{:.0f}",
    "lockFailPct":    "{:.3f}",
    "rank_mean":      "{:.2f}",
    "rank_p99":       "{:.0f}",
    "rank_max":       "{:.0f}",
    "rank_count":     "{:.0f}",
}
BEGIN, END, DONE = "===CSV_BEGIN===", "===CSV_END===", "=== MPP RUN COMPLETE ==="


def blocks_from(text, path):
    """Yield each CSV block in one capture (as a DataFrame), sanity-checking the run."""
    if DONE not in text:
        sys.exit(f"ERROR: {path} has no '{DONE}' marker — run was cut off; discard and re-run.")
    fails = [ln for ln in text.splitlines() if "Gate " in ln and "PASS" not in ln]
    if fails:
        sys.exit(f"ERROR: {path} has a non-PASS gate:\n  " + "\n  ".join(fails))
    n = 0
    lines, capturing = [], False
    for ln in text.splitlines():
        if ln.strip() == BEGIN:
            lines, capturing = [], True
            continue
        if ln.strip() == END:
            capturing = False
            csv = "\n".join(l for l in lines if l and not l.startswith("---"))
            yield pd.read_csv(io.StringIO(csv))
            n += 1
            continue
        if capturing:
            lines.append(ln)
    if n == 0:
        sys.exit(f"ERROR: {path} contained no {BEGIN}/{END} block.")


def main(paths):
    frames, runs = [], 0
    for p in paths:
        with open(p) as fh:
            text = fh.read()
        for df in blocks_from(text, p):
            frames.append(df)
            runs += 1
    if not frames:
        sys.exit("ERROR: no runs to average.")

    allrows = pd.concat(frames, ignore_index=True)
    # keep the first run's row order so the CSV stays diff-friendly
    order = frames[0][KEY].astype(str).agg("|".join, axis=1).tolist()
    grouped = allrows.groupby(KEY, sort=False, dropna=False)[list(VAL)].mean().reset_index()
    grouped["_k"] = grouped[KEY].astype(str).agg("|".join, axis=1)
    rank = {k: i for i, k in enumerate(order)}
    grouped = grouped.sort_values("_k", key=lambda s: s.map(lambda k: rank.get(k, 1 << 30))).drop(columns="_k")

    for col, fmt in VAL.items():
        grouped[col] = grouped[col].map(lambda v, f=fmt: f.format(v))

    grouped = grouped[KEY + list(VAL)]
    sys.stderr.write(f"averaged {runs} run(s) across {len(paths)} file(s) -> {len(grouped)} rows\n")
    grouped.to_csv(sys.stdout, index=False)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
