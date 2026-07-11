# MultiQueue: a relaxed concurrent priority queue, where its rank-error guarantee breaks, and a thread-local fix

I implement the **MultiQueue**, a relaxed concurrent priority queue, in Java 8 and study it under non-uniform load. It builds on three papers. Rihani, Sanders & Dementiev (SPAA 2015) [1] introduce the structure: `c·p` internal sequential heaps, each behind its own lock, with `insert` going to a uniform-random queue and `deleteMin` sampling `d` queues and popping the smaller of their cached minima. Williams, Sanders & Dementiev (ESA 2021) [2] engineer it and define the **rank error** quality metric. Walzer & Williams (ESA 2025) [3] prove the expected rank error is exactly `(5/6)·n − 1 + 1/(6n)` at `d=2` with `n = c·p`. That guarantee assumes a uniformly random load. My project asks what happens when the load is not uniform, and what I can do about it.

## The three-part project in brief

The project has three parts: a *problem* the papers do not analyze, a *variation* of the sampling width, and an *improvement* to the sampling rule. All numbers come from real runs of the Java 8 code on the college's 96-core server.

1. **Problem: skew breaks the bound (~4×).** Under a non-uniform *placement* workload (small keys routed to a few "hot" queues), the measured mean rank error is about 4× the uniform-load prediction: **27.56 vs 6.67** at `n=8`, `d=2` (4.13×). Changing only key *order* while keeping placement uniform (a Dijkstra-like MONOTONE workload) is harmless (2.25). Rank error is ordinal, so what the guarantee depends on is non-uniform placement, not key randomness.
2. **Variation: the `d` sweep trade-off.** Widening the sample from `d=1` to `d=8` makes rank error fall steeply (on SKEWED, from **1756.5 to 1.7**) while throughput declines about **28%** over the useful `d=2–8` range. The biggest single gain is the first extra choice at `d=2` (SKEWED 1756→36, ~49×), so `d=2` is the sensible default. That raises the Part 3 question: can I get better quality at `d=2` without paying for extra samples?
3. **Improvement: STALE → WINNER.** I *chose* to start from biasing `deleteMin` toward the least-recently-sampled queue (**STALE**). My advisor approved it with one constraint: no *global* per-queue tracker, since that would just re-create the bottleneck the MultiQueue exists to remove. The signal has to stay thread-local, with at most occasional synchronization. I implemented STALE, measured it, and found it weak (only ~18% better), because the least-recently-sampled queue has nothing to do with where the small elements actually are. So I improved it into **WINNER**: a thread-local bias toward *recently winning* queues (the "power of two choices with memory" [4], moved from the insertion side to the deletion side). WINNER cuts SKEWED rank error by about **61%** with no shared writes and no herding.

## Repository layout

```
multiqueue-relaxed-pq/
  README.md
  LICENSE                     MIT, Yacov Kourdi, 2026
  .gitignore
  src/                        Java 8 sources (no packages, main class MppRunner)
    MppRunner.java            entry point: correctness gates + the four experiments
    MultiQueue.java           the relaxed concurrent PQ; SampleStrategy = UNIFORM / STALE / WINNER
    RankError.java            exact sequential rank-error measurement (Fenwick tree)
    SeqHeap.java              sequential array-backed binary min-heap (long[])
    StrictPQ.java             baseline: one SeqHeap behind one lock (exact, non-scalable)
    Workloads.java            UNIFORM / MONOTONE / SKEWED key-and-placement models
  report/
    report.pdf               the report (PDF)
    report.html              same report, source form (all numbers inline)
  bench/
    SERVER_RUN.md            how it runs on the college server (authoritative environment)
    average_runs.py          slice + validate + average the raw server captures into a CSV
    plot_bench.py            render the five report figures from the CSV
    bench_results.csv        the averaged CSV the plotter reads
    figures/                 fig1_part1_weakness.png ... fig5_scalability.png
    results/                 run1.out  run2.out  run3.out   (raw authoritative server captures)
  artifacts/
    server_upload/           the 17 compiled Java 8 .class files uploaded to the server
    mpp.out                  a reference run capture
    smoke.out                a reference smoke-test capture
```

## Build & run

Clone the repo, then compile and run:

```bash
javac --release 8 -d out src/*.java
java -cp out MppRunner
```

`MppRunner` takes two optional positional arguments (milliseconds per measurement and repeat count), so a quick smoke run is:

```bash
java -cp out MppRunner 300 1
```

This needs a real JDK on the path (a stub `java` shim will not run it). The **college server is the authoritative environment** for these results. See [`bench/SERVER_RUN.md`](bench/SERVER_RUN.md) for the exact upload/run recipe there, and [`artifacts/server_upload/`](artifacts/server_upload/) for the compiled Java 8 class set that gets uploaded. The committed figures are the **average of three runs on the college server** (`java.version 17.0.15`, 96 hardware threads).

To reproduce the figures from the raw server captures:

```bash
python3 bench/average_runs.py bench/results/run*.out > bench/bench_results.csv
python3 bench/plot_bench.py bench/bench_results.csv
```

## Results at a glance

All numbers are from real runs of the Java 8 code; rank error is measured exactly (a single-threaded driver mirroring the live queue in a Fenwick tree / binary indexed tree [6], since the analyses are stated for sequential execution), averaged over repeats.

**Why a Fenwick tree.** Each `deleteMin` asks one question: *of the keys still in the queue, how many are smaller than the one I just removed?* That is a running total (a prefix sum) over the key range. If I kept the counts in a plain array I would have to add up a whole slice of it for every query, and I ask this question hundreds of thousands of times per run. A Fenwick tree stores those partial sums so that both "one more key is present" and "count everything below `k`" take about `log(U)` steps — roughly 20 for our universe of `2^20` — instead of walking the whole range. That is what keeps the exact rank-error sweep fast enough to run every configuration many times over.

| Part | Measurement | Result |
|------|-------------|--------|
| **1 — problem** | mean rank error, `n=8`, `d=2` (theory = `(5/6)·8 = 6.67`) | UNIFORM **5.62** (0.84×) · MONOTONE **2.25** (0.34×) · SKEWED **27.56** (4.13×) |
| **2 — variation** | SKEWED mean rank error, `p=8`, `n=16` | `d=1` **1756.5** → `d=2` **35.6** → `d=4` **4.2** → `d=8` **1.7**; throughput ~**28%** lower from `d=2` to `d=8` |
| **3 — improvement** | SKEWED mean rank error, `d=2`, `n=16` (baseline UNIFORM **37.78**) | STALE **30.92** (~18%) · WINNER `M=1` **13.71** · WINNER `M=4, ε=⅛` **14.74** — about **61%** off the mean, tail `p99` **615→80** (~8×) |
| | robustness | WINNER holds under `p=8` interleaved execution (**29.2→14.3**) and does not herd: throughput ~**14 M ops/s** vs UNIFORM **13.6 M** / STALE **13.9 M**, and a *shared* tracker collapses throughput **14.2→3.6 M ops/s** (~4×) |
| **scalability** | throughput vs thread count | StrictPQ peaks at **22.3 M ops/s** (1 thread), flattens to ~**13 M**; MultiQueue starts at **15.4 M**, overtakes by 8 threads, reaches **54.1 M ops/s** at 64 threads (~4× the strict queue) |

So the `(5/6)·n` guarantee is real but conditional on uniform load. The `d` parameter trades throughput for quality, with a knee at `d=2`. And a strictly thread-local, content-aware bias (WINNER) recovers most of the lost quality under skew without the coordination cost that would otherwise eat the gain.

## Report

The full write-up (abstract, paper summaries, method, all three parts, and the references) is in [`report/report.pdf`](report/report.pdf).

## References

1. H. Rihani, P. Sanders, R. Dementiev. *MultiQueues: Simple Relaxed Concurrent Priority Queues.* SPAA 2015, pp. 80–82. Preprint: arXiv:1411.1209.
2. M. Williams, P. Sanders, R. Dementiev. *Engineering MultiQueues: Fast Relaxed Concurrent Priority Queues.* ESA 2021, LIPIcs 204, 81:1–81:17. doi:10.4230/LIPIcs.ESA.2021.81. Extended preprint: arXiv:2504.11652.
3. S. Walzer, M. Williams. *A Simple yet Exact Analysis of the MultiQueue.* ESA 2025, LIPIcs. doi:10.4230/LIPIcs.ESA.2025.85. Preprint: arXiv:2410.08714.
4. M. Mitzenmacher, B. Prabhakar, D. Shah. *Load Balancing with Memory.* IEEE FOCS 2002, pp. 799–808. (The "power of two choices with memory"; used here as motivation, not as a bound, since a deletion priority queue is not the static balls-into-bins setting it analyses.)
5. V. Gramoli. *More Than You Ever Wanted to Know about Synchronization (Synchrobench).* PPoPP 2015. Benchmarking methodology for concurrent data structures.
6. Fenwick tree (binary indexed tree). CP-Algorithms. https://cp-algorithms.com/data_structures/fenwick.html. Used only in the measurement driver, not in the MultiQueue itself, to make the exact prefix-count rank query `O(log U)`.
