# MultiQueue under non-uniform load

A Java 8 implementation of the MultiQueue relaxed concurrent priority queue, plus a
benchmark harness that measures its rank error and throughput. The project studies
what happens to the rank-error guarantee when the load is skewed, sweeps the sampling
width `d`, and adds a thread-local sampling bias (WINNER) that recovers quality under
skew. The full write-up is in [`report/report.pdf`](report/report.pdf).

## Build and run

```bash
javac --release 8 -d out src/*.java
java -cp out MppRunner          # full run
java -cp out MppRunner 300 1    # quick run: ms per measurement, repeat count
```

Needs a real JDK. Compiles to Java 8 bytecode (`--release 8`) so it runs unchanged on
the college server. The committed figures are the average of three runs on that
96-core server; see [`bench/SERVER_RUN.md`](bench/SERVER_RUN.md) for the upload/run
recipe and [`artifacts/server_upload/`](artifacts/server_upload/) for the compiled
class set.

Regenerate the figures from the raw captures:

```bash
python3 bench/average_runs.py bench/results/run*.out > bench/bench_results.csv
python3 bench/plot_bench.py bench/bench_results.csv
```

## Layout

```
src/           Java 8 sources, no packages, main class MppRunner
report/        report.pdf (the write-up) and report.html (its source)
bench/         SERVER_RUN.md, the averaging and plotting scripts, CSV, figures/, raw run captures
artifacts/     compiled .class set for the server, plus reference run captures
```

## References

1. H. Rihani, P. Sanders, R. Dementiev. *MultiQueues: Simple Relaxed Concurrent Priority Queues.* SPAA 2015. arXiv:1411.1209.
2. M. Williams, P. Sanders, R. Dementiev. *Engineering MultiQueues: Fast Relaxed Concurrent Priority Queues.* ESA 2021, LIPIcs 204, 81:1–81:17. arXiv:2504.11652.
3. S. Walzer, M. Williams. *A Simple yet Exact Analysis of the MultiQueue.* ESA 2025, LIPIcs. arXiv:2410.08714.
4. M. Mitzenmacher, B. Prabhakar, D. Shah. *Load Balancing with Memory.* IEEE FOCS 2002, pp. 799–808.
5. V. Gramoli. *More Than You Ever Wanted to Know about Synchronization (Synchrobench).* PPoPP 2015.
6. Fenwick tree (binary indexed tree). CP-Algorithms. https://cp-algorithms.com/data_structures/fenwick.html
