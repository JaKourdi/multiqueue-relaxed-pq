# Authoritative server run: copy-paste guide

The college server is the graded Java environment for this course, so I run the
correctness gates and benchmark CSV there, then pull the output back to regenerate
the report figures and numbers locally.

I've checked this end to end. A clean `javac --release 8` compile produces
**17 `.class` files, all bytecode major version 52 (Java 8)**. The class-only set in
`server_upload/` runs standalone, and all three correctness gates print `PASS`.

---

## 0. What goes to the server

Upload **only** the 17 `.class` files in [`server_upload/`](server_upload/). No `.java`,
no folders, no `mpp.out`. The course rule is strict about this: any extra file blocks
the run.

```
server_upload/
  MppRunner.class            MultiQueue.class              RankError.class
  MppRunner$1.class          MultiQueue$1.class            RankError$RankStats.class
  MppRunner$2.class          MultiQueue$QueueWrapper.class SeqHeap.class
  MppRunner$3.class          MultiQueue$SampleStrategy.class StrictPQ.class
  MppRunner$4.class          MultiQueue$ThreadSampleState.class Workloads.class
  MppRunner$Thr.class                                      Workloads$1.class
```

The server runs `MppRunner.main` and writes stdout+stderr into `mpp.out`. Delete
`mpp.out` before re-running.

---

## 1. On the server (shared OneDrive / run folder)

```bash
# 1a. clear the run folder of any previous upload + stale output
#     (do this in the shared folder; leave nothing but the new .class set)

# 1b. upload the 17 .class files from server_upload/  (portal / OneDrive / scp)

# 1c. the server runs `java MppRunner` and captures mpp.out.
#     Default run = 1500 ms/measurement, 3 repeats. These are the exact params
#     behind the committed report numbers, so no args are needed to match them:
#        java MppRunner
#     (equivalently `java MppRunner 1500 3`).
```

If you have an interactive shell instead of a drop-folder, compile and run directly:

```bash
# only if you can also upload the .java sources; otherwise use the .class set above
javac --release 8 *.java          # must be --release 8 (server is Java 8)
java MppRunner > mpp.out 2>&1      # banners on stderr, CSV on stdout, both captured
```

**Run it 2–3 separate times** and keep each output. The server can be loaded near
deadlines, so I average throughput across runs, same as in HW3. Concatenate them:

```bash
cat run1.out run2.out run3.out > mpp.out.server
```

Each run must end with `=== MPP RUN COMPLETE ===`. If it doesn't, the run was cut off
(loaded server or a timeout). Discard it and re-run.

---

## 2. Sanity checks on the server output

```bash
grep -E "java.version|availableProcessors|durationMs" mpp.out   # record the environment
grep -E "Gate [123].*(PASS|FAIL)"            mpp.out            # all three must be PASS
grep -c "=== MPP RUN COMPLETE ==="           mpp.out.server     # == number of runs kept
```

Expected gate lines:

```
Gate 1 (numQueues=1 exact ordering): PASS
Gate 2 (concurrent multiset preservation): PASS
Gate 3 (WINNER multiset preservation): PASS
```

> A note on the environment. HW3's runs of this same `MppRunner` harness reported
> `Java 8, availableProcessors = 96` on the college server. `--release 8`
> bytecode runs unchanged there, but **96 cores ≠ the 16-core box the committed numbers
> came from**, so the fresh throughput and rank figures will differ. That's fine.
> §3 covers regenerating the report from whatever the server actually produces.

---

## 3. Back on this machine: regenerate figures and numbers from the server output

`MppRunner` prints the CSV between `===CSV_BEGIN===` and `===CSV_END===`. `plot_bench.py`
expects a **clean CSV**, not raw `mpp.out`. **The report averages 2–3 server runs**
(HW3's method; the server can be loaded near deadlines, so I smooth throughput across
runs). `average_runs.py` handles the slice, validate, and average in one step:

```bash
cd a/multicore/project/submission

# save each server capture under res/ (e.g. res/run1.out, res/run2.out, res/run3.out),
# then average them all into the clean CSV the plotter reads:
python3 average_runs.py res/run1.out res/run2.out res/run3.out > bench_results.csv

python3 plot_bench.py bench_results.csv     # rewrites fig1..fig5 *.png in place
```

`average_runs.py` rejects any capture missing all-PASS gates or the
`=== MPP RUN COMPLETE ===` marker (that means the run was cut off). It pools every CSV
block it finds, so a single concatenated file works too, and averages the value columns
keyed by experiment identity. Quality columns (`rank_*`) are already internally averaged
and machine-independent, so averaging is a no-op on those. Throughput and lock-fail are
the columns that actually benefit. Averaging a single run reproduces it byte-for-byte.

> Single-run fallback: if you keep only one run, `python3 average_runs.py res/mpp.out >
> bench_results.csv` is equivalent to the old `awk` slice and still valid. The report
> then states "one server run" instead of an average.

Then update the report numbers to match the new `bench_results.csv` and rebuild the PDF:

```bash
# report.html holds every number inline; update the tables/abstract to the server CSV,
# then render report.pdf from report.html (same tool used for the committed PDF).
```

Commit the refreshed `bench_results.csv`, `fig1..5.png`, `report.html/pdf`, and save the
raw server capture as `mpp.out.server` (the authoritative-run artifact, mirroring HW3's
`mpp.out.server3`).

---

## Local reproduction (for reference, NOT authoritative)

`/usr/local/bin/java` on this Mac is a Salesforce shim that only prints a notice. Use
the real Azul JDK for any local run:

```bash
export JAVA_HOME=/Users/ykourdi/Library/Java/JavaVirtualMachines/azul-17.0.18/Contents/Home
"$JAVA_HOME/bin/javac" --release 8 -d server_upload *.java   # rebuild the upload set
"$JAVA_HOME/bin/java"  MppRunner 1500 3 > mpp.out 2> mpp.err # full local run
```

Verify the upload set is Java 8 before shipping:

```bash
for f in server_upload/*.class; do
  [ "$(xxd -s 6 -l 2 -p "$f")" = "0034" ] || echo "NOT Java 8: $f"
done   # 0x0034 = major 52 = Java 8; silence == all good
```
