import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

// Server entry point. Plain `java MppRunner [durationMs] [repeats]`, no JVM flags needed.
// All the printing happens after the timed/parallel regions finish. Machine-readable
// rows go out between ===CSV_BEGIN=== / ===CSV_END=== for plot_bench.py to pick up.
//
// Experiments:
//   0  correctness gates (numQueues=1 exact; concurrent multiset preservation)
//   1  Part 1 (problem):    rank error vs theory under UNIFORM / MONOTONE / SKEWED load
//   2  Part 2 (variation):  d-sweep, throughput vs quality trade-off
//   3  Part 3 (improvement), two thread-local sampling biases compared head to head:
//        part3q     sequential exact rank error: UNIFORM vs STALE vs WINNER (the headline quality)
//        part3eps   WINNER exploration sweep, the quality/contention dial ("compare the options")
//        part3thr   concurrent throughput + deleteMin lock-fail rate per strategy (herding check)
//        part3sync  STALE shared-signal sync spectrum (never/64/1), the global-tracker bottleneck
//        part3cq    concurrent interleaved exact rank error, does the sequential win survive concurrency
//   4  scalability: StrictPQ vs MultiQueue throughput vs thread count
public class MppRunner {

    static long durationMs = 1500;
    static int repeats = 3;
    static final int SEQ_REPS = 7;        // avg over this many reps, seed noise otherwise dominates
    static final int SEQ_MEASURED = 300000;
    static final int CQ_WARMUP = 20000, CQ_MEASURED = 200000, CQ_REPS = 5; // concurrent quality run

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) durationMs = Long.parseLong(args[0]);
        if (args.length >= 2) repeats = Integer.parseInt(args[1]);

        System.out.println("=== MPP RUN START: MultiQueue (relaxed concurrent priority queue) ===");
        System.out.println("java.version = " + System.getProperty("java.version")
                + ", availableProcessors = " + Runtime.getRuntime().availableProcessors());
        System.out.println("durationMs = " + durationMs + ", repeats = " + repeats);

        correctness();

        System.out.println("===CSV_BEGIN===");
        System.out.println("experiment,strategy,workload,d,c,threads,syncPeriod,ringSize,"
                + "throughput_ops,lockFailPct,rank_mean,rank_p99,rank_max,rank_count");
        part1_exposeWeakness();
        part2_dSweep();
        part3_improvement();
        scalability();
        System.out.println("===CSV_END===");

        System.out.println("=== MPP RUN COMPLETE ===");
    }

    // ---------- Experiment 0: correctness ----------

    static void correctness() {
        System.out.println("--- correctness ---");

        MultiQueue one = new MultiQueue(1);
        long[] keys = {50, 10, 30, 20, 40, 10};
        for (long k : keys) one.insert(k);
        long prev = Long.MIN_VALUE;
        boolean sorted = true;
        for (int i = 0; i < keys.length; i++) {
            long k = one.deleteMin(1, MultiQueue.SampleStrategy.UNIFORM, MultiQueue.NEVER_SYNC, null);
            if (k < prev) sorted = false;
            prev = k;
        }
        System.out.println("Gate 1 (numQueues=1 exact ordering): " + (sorted ? "PASS" : "FAIL"));

        boolean preserved = multisetPreserved();
        System.out.println("Gate 2 (concurrent multiset preservation): " + (preserved ? "PASS" : "FAIL"));

        // Gate 3: WINNER hints are best-effort, but still shouldn't change the multiset.
        boolean winnerSafe = winnerMultisetPreserved();
        System.out.println("Gate 3 (WINNER multiset preservation): " + (winnerSafe ? "PASS" : "FAIL"));
        if (!sorted || !preserved || !winnerSafe) System.err.println("CORRECTNESS FAILURE");
    }

    static boolean multisetPreserved() {
        final int P = 4, N = 2000, c = 2;
        final MultiQueue mq = new MultiQueue(c * P);
        final Map<Long, Integer> in = new HashMap<Long, Integer>();
        final Object lock = new Object();
        CountDownLatch latch = new CountDownLatch(P);
        for (int t = 0; t < P; t++) {
            new Thread(new Runnable() {
                public void run() {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < N; i++) {
                        long k = rng.nextInt(100000);
                        synchronized (lock) { in.merge(k, 1, Integer::sum); }
                        mq.insert(k);
                    }
                    latch.countDown();
                }
            }).start();
        }
        try { latch.await(); } catch (InterruptedException e) { return false; }

        Map<Long, Integer> out = new HashMap<Long, Integer>();
        int total = P * N, drained = 0, empties = 0;
        while (drained < total) {
            long k = mq.deleteMin();
            if (k == Long.MAX_VALUE) { if (++empties > 1000) break; continue; }
            out.merge(k, 1, Integer::sum);
            drained++;
        }
        return in.equals(out);
    }

    // drain a WINNER-sampled queue single threaded, check the multiset comes out exact.
    // just making sure the remembered-winner hint can't drop/dup/invent an element.
    static boolean winnerMultisetPreserved() {
        int numQueues = 8;
        MultiQueue mq = new MultiQueue(numQueues);
        MultiQueue.ThreadSampleState st = mq.newThreadState();
        Map<Long, Integer> in = new HashMap<Long, Integer>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int N = 20000;
        for (int i = 0; i < N; i++) { long k = rng.nextInt(100000); in.merge(k, 1, Integer::sum); mq.insert(k); }
        Map<Long, Integer> out = new HashMap<Long, Integer>();
        int drained = 0, empties = 0;
        while (drained < N) {
            long k = mq.deleteMin(2, MultiQueue.SampleStrategy.WINNER, 8, st);
            if (k == Long.MAX_VALUE) { if (++empties > 1000) break; continue; }
            out.merge(k, 1, Integer::sum);
            drained++;
        }
        return in.equals(out);
    }

    // ---------- Experiment 1: expose the weakness ----------

    static void part1_exposeWeakness() {
        int P = 4, c = 2, numQueues = c * P;
        double theory = (5.0 / 6.0) * numQueues;
        System.err.println("--- part1: rank error vs theory (5/6*numQueues = "
                + String.format("%.2f", theory) + "), numQueues=" + numQueues + " ---");
        for (Workloads wl : new Workloads[]{Workloads.UNIFORM, Workloads.MONOTONE, Workloads.SKEWED}) {
            RankError.RankStats s = rankErrorSeq(numQueues, 2, MultiQueue.SampleStrategy.UNIFORM,
                    MultiQueue.NEVER_SYNC, 0, wl);
            row("part1", "UNIFORM", wl, 2, c, P, MultiQueue.NEVER_SYNC, 0, 0, 0, s);
        }
    }

    // ---------- Experiment 2: d-sweep, throughput vs quality ----------

    static void part2_dSweep() {
        int P = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        int c = 2, numQueues = c * P;
        System.err.println("--- part2: d-sweep (throughput vs quality), P=" + P + " ---");
        for (Workloads wl : new Workloads[]{Workloads.UNIFORM, Workloads.SKEWED}) {
            for (int d : new int[]{1, 2, 4, 8}) {
                Thr thr = throughput(numQueues, P, d, MultiQueue.SampleStrategy.UNIFORM,
                        MultiQueue.NEVER_SYNC, 0, wl);
                RankError.RankStats s = rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.UNIFORM,
                        MultiQueue.NEVER_SYNC, 0, wl);
                row("part2", "UNIFORM", wl, d, c, P, MultiQueue.NEVER_SYNC, 0, thr.thr, thr.lockFailPct, s);
            }
        }
    }

    // ---------- Experiment 3: the Part-3 improvement ----------

    static void part3_improvement() {
        int P = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        int c = 2, numQueues = c * P, d = 2;
        System.err.println("--- part3: thread-local sampling biases (STALE vs WINNER), P=" + P + " ---");

        // (a) quality is a sequential property (the analyses are all sequential), so exact rank
        // error for UNIFORM vs the STALE bias vs WINNER (power-of-two-choices, with memory).
        // WINNER variants: M=1 greedy (just "remember the winner"), M=4 greedy (ring only), and
        // M=4 with 1/8 exploration (the headline one). UNIFORM/MONOTONE are controls, SKEWED is
        // the failure case the improvement is aimed at.
        for (Workloads wl : new Workloads[]{Workloads.UNIFORM, Workloads.MONOTONE, Workloads.SKEWED}) {
            qRow("part3q", "UNIFORM", wl, d, c, P, MultiQueue.NEVER_SYNC, 0,
                    rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.UNIFORM, MultiQueue.NEVER_SYNC, 0, wl));
            qRow("part3q", "STALE", wl, d, c, P, MultiQueue.NEVER_SYNC, 0,
                    rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.STALE, MultiQueue.NEVER_SYNC, 0, wl));
            qRow("part3q", "WINNER", wl, d, c, P, MultiQueue.NEVER_SYNC, 1,
                    rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.WINNER, MultiQueue.NEVER_SYNC, 1, wl));
            qRow("part3q", "WINNER", wl, d, c, P, MultiQueue.NEVER_SYNC, 4,
                    rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.WINNER, MultiQueue.NEVER_SYNC, 4, wl));
            qRow("part3q", "WINNER", wl, d, c, P, 8, 4,
                    rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.WINNER, 8, 4, wl));
        }
        // control for "why not just raise d": UNIFORM at d=3 and d=4 on SKEWED. lets the report
        // show WINNER@d=2 gets roughly d=3 quality for d=2 sampling cost.
        for (int dd : new int[]{3, 4}) {
            qRow("part3q", "UNIFORM", Workloads.SKEWED, dd, c, P, MultiQueue.NEVER_SYNC, 0,
                    rankErrorSeq(numQueues, dd, MultiQueue.SampleStrategy.UNIFORM, MultiQueue.NEVER_SYNC, 0, Workloads.SKEWED));
        }

        // (b) exploration sweep, the quality/contention dial (the "compare the options" part).
        // WINNER (M=4), varying the forced-exploration period: greedy (never), 1/16, 1/8, 1/4.
        // quality measured sequentially; throughput + lock-fail rate measured concurrently.
        System.err.println("--- part3eps: WINNER exploration dial on SKEWED ---");
        int[] explore = {MultiQueue.NEVER_SYNC, 16, 8, 4};
        for (int ep : explore) {
            RankError.RankStats q = rankErrorSeq(numQueues, d, MultiQueue.SampleStrategy.WINNER, ep, 4, Workloads.SKEWED);
            Thr thr = throughput(numQueues, P, d, MultiQueue.SampleStrategy.WINNER, ep, 4, Workloads.SKEWED);
            row("part3eps", "WINNER", Workloads.SKEWED, d, c, P, ep, 4, thr.thr, thr.lockFailPct, q);
        }

        // (c) concurrent throughput + deleteMin lock-fail rate per strategy on SKEWED. lock-fail
        // rate is the herding signal here, if WINNER threads all pile onto the same hot queues the
        // tryLock-fail rate spikes. this is concurrent so quality isn't recorded.
        System.err.println("--- part3thr: concurrent throughput + lock-fail rate per strategy ---");
        RankError.RankStats z = new RankError.RankStats(0, 0, 0, 0);
        Thr u = throughput(numQueues, P, d, MultiQueue.SampleStrategy.UNIFORM, MultiQueue.NEVER_SYNC, 0, Workloads.SKEWED);
        row("part3thr", "UNIFORM", Workloads.SKEWED, d, c, P, MultiQueue.NEVER_SYNC, 0, u.thr, u.lockFailPct, z);
        Thr stl = throughput(numQueues, P, d, MultiQueue.SampleStrategy.STALE, MultiQueue.NEVER_SYNC, 0, Workloads.SKEWED);
        row("part3thr", "STALE", Workloads.SKEWED, d, c, P, MultiQueue.NEVER_SYNC, 0, stl.thr, stl.lockFailPct, z);
        Thr wg = throughput(numQueues, P, d, MultiQueue.SampleStrategy.WINNER, MultiQueue.NEVER_SYNC, 4, Workloads.SKEWED);
        row("part3thr", "WINNER", Workloads.SKEWED, d, c, P, MultiQueue.NEVER_SYNC, 4, wg.thr, wg.lockFailPct, z);
        Thr we = throughput(numQueues, P, d, MultiQueue.SampleStrategy.WINNER, 8, 4, Workloads.SKEWED);
        row("part3thr", "WINNER", Workloads.SKEWED, d, c, P, 8, 4, we.thr, we.lockFailPct, z);

        // (d) original part-3 finding, kept for reference: force the STALE signal to be global
        // (sync every op) and it just recreates the bottleneck the MultiQueue is trying to avoid.
        // WINNER has no shared state so there's nothing analogous to collapse.
        System.err.println("--- part3sync: cost of sharing the STALE signal ---");
        for (int sp : new int[]{MultiQueue.NEVER_SYNC, 64, 1}) {
            Thr thr = throughput(numQueues, P, d, MultiQueue.SampleStrategy.STALE, sp, 0, Workloads.SKEWED);
            row("part3sync", "STALE", Workloads.SKEWED, d, c, P, sp, 0, thr.thr, thr.lockFailPct, z);
        }

        // (e) falsification check, does the sequential quality win actually survive real
        // multi-threaded interleaving (a thread's remembered winner could get drained by someone
        // else between its own ops). exact rank error under P-thread interleaved execution.
        System.err.println("--- part3cq: concurrent interleaved exact rank error ---");
        qRow("part3cq", "UNIFORM", Workloads.SKEWED, d, c, P, MultiQueue.NEVER_SYNC, 0,
                concurrentQuality(numQueues, P, d, MultiQueue.SampleStrategy.UNIFORM, MultiQueue.NEVER_SYNC, 0, Workloads.SKEWED));
        qRow("part3cq", "STALE", Workloads.SKEWED, d, c, P, MultiQueue.NEVER_SYNC, 0,
                concurrentQuality(numQueues, P, d, MultiQueue.SampleStrategy.STALE, MultiQueue.NEVER_SYNC, 0, Workloads.SKEWED));
        qRow("part3cq", "WINNER", Workloads.SKEWED, d, c, P, 8, 4,
                concurrentQuality(numQueues, P, d, MultiQueue.SampleStrategy.WINNER, 8, 4, Workloads.SKEWED));
    }

    // ---------- Experiment 4: scalability ----------

    static void scalability() {
        int maxT = Runtime.getRuntime().availableProcessors();
        System.err.println("--- scale: StrictPQ vs MultiQueue throughput vs threads ---");
        RankError.RankStats z = new RankError.RankStats(0, 0, 0, 0);
        for (int P : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            if (P > maxT) break;
            long strict = strictThroughput(P);
            Thr mq = throughput(2 * P, P, 2, MultiQueue.SampleStrategy.UNIFORM,
                    MultiQueue.NEVER_SYNC, 0, Workloads.UNIFORM);
            row("scale", "StrictPQ", Workloads.UNIFORM, 0, 0, P, 0, 0, strict, 0, z);
            row("scale", "MultiQueue", Workloads.UNIFORM, 2, 2, P, MultiQueue.NEVER_SYNC, 0, mq.thr, mq.lockFailPct, z);
        }
    }

    // ---------- measurement: exact sequential rank error ----------

    // runs the actual MultiQueue single-threaded and mirrors its contents in an exact Fenwick
    // multiset, so every deleteMin's rank error comes out exact, no sampling noise. this matches
    // the sequential setting the rank-error analyses assume (Walzer & Williams, ESA 2025).
    static RankError.RankStats rankErrorSeq(int numQueues, int d, MultiQueue.SampleStrategy strat,
                                            int syncPeriod, int ringSize, Workloads wl) {
        double meanSum = 0;
        long p99Sum = 0, maxSum = 0, countSum = 0;
        for (int r = 0; r < SEQ_REPS; r++) {
            RankError.RankStats s = rankErrorSeqOnce(numQueues, d, strat, syncPeriod, ringSize, wl);
            meanSum += s.mean; p99Sum += s.p99; maxSum += s.max; countSum += s.count;
        }
        return new RankError.RankStats(meanSum / SEQ_REPS, p99Sum / SEQ_REPS,
                maxSum / SEQ_REPS, countSum / SEQ_REPS);
    }

    static RankError.RankStats rankErrorSeqOnce(int numQueues, int d, MultiQueue.SampleStrategy strat,
                                                int syncPeriod, int ringSize, Workloads wl) {
        MultiQueue mq = new MultiQueue(numQueues);
        RankError tracker = new RankError();
        MultiQueue.ThreadSampleState st =
                (strat == MultiQueue.SampleStrategy.UNIFORM) ? null : mq.newThreadState(ringSize);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int prefill = 4000 + 300 * numQueues;
        long last = 0;
        for (int i = 0; i < prefill; i++) {
            long k = wl.nextKey(rng, last);
            place(mq, wl, k, numQueues);
            tracker.add(k);
        }

        int warmup = 20000, measured = SEQ_MEASURED;
        for (int i = 0; i < warmup + measured; i++) {
            boolean record = i >= warmup;
            if (rng.nextBoolean()) {
                long k = wl.nextKey(rng, last);
                place(mq, wl, k, numQueues);
                tracker.add(k);
            } else {
                long k = mq.deleteMin(d, strat, syncPeriod, st);
                if (k == Long.MAX_VALUE) continue;
                last = k;
                if (record) tracker.observeAndRemove(k); else tracker.remove(k);
            }
        }
        return tracker.snapshot();
    }

    static void place(MultiQueue mq, Workloads wl, long k, int numQueues) {
        int idx = wl.placement(k, numQueues);
        if (idx < 0) mq.insert(k); else mq.insertAt(idx, k);
    }

    // ---------- measurement: concurrent interleaved exact rank error ----------

    // all P threads do a 50/50 insert/deleteMin mix, but each op goes through one audit monitor
    // that also updates a shared exact Fenwick multiset, so rank error stays exact. ops from the
    // P threads interleave with per-thread WINNER memory and a shared cached-min view, so a
    // thread's remembered winner can get drained by someone else between its own ops. that's the
    // concurrency effect a single-threaded measurement just can't show. (wall clock doesn't matter
    // here, throughput gets measured separately in part3thr.)
    static RankError.RankStats concurrentQuality(int numQueues, int P, int d,
                                                 MultiQueue.SampleStrategy strat, int syncPeriod,
                                                 int ringSize, Workloads wl) {
        double meanSum = 0; long p99Sum = 0, maxSum = 0, countSum = 0;
        for (int r = 0; r < CQ_REPS; r++) {
            RankError.RankStats s = concurrentQualityOnce(numQueues, P, d, strat, syncPeriod, ringSize, wl);
            meanSum += s.mean; p99Sum += s.p99; maxSum += s.max; countSum += s.count;
        }
        return new RankError.RankStats(meanSum / CQ_REPS, p99Sum / CQ_REPS, maxSum / CQ_REPS, countSum / CQ_REPS);
    }

    static RankError.RankStats concurrentQualityOnce(final int numQueues, final int P, final int d,
                                                     final MultiQueue.SampleStrategy strat,
                                                     final int syncPeriod, final int ringSize,
                                                     final Workloads wl) {
        final MultiQueue mq = new MultiQueue(numQueues);
        final RankError tracker = new RankError();
        final Object audit = new Object();
        ThreadLocalRandom rng0 = ThreadLocalRandom.current();
        int prefill = 4000 + 300 * numQueues;
        long last0 = 0;
        for (int i = 0; i < prefill; i++) { long k = wl.nextKey(rng0, last0); place(mq, wl, k, numQueues); tracker.add(k); }

        final int[] counter = {0}; // guarded by `audit`
        final int total = CQ_WARMUP + CQ_MEASURED;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(P);
        for (int t = 0; t < P; t++) {
            new Thread(new Runnable() {
                public void run() {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    MultiQueue.ThreadSampleState st =
                            (strat == MultiQueue.SampleStrategy.UNIFORM) ? null : mq.newThreadState(ringSize);
                    long last = 0;
                    try { start.await(); } catch (InterruptedException e) { return; }
                    boolean finished = false;
                    while (!finished) {
                        synchronized (audit) {
                            int idx = counter[0];
                            if (idx >= total) { finished = true; }
                            else {
                                counter[0] = idx + 1;
                                boolean record = idx >= CQ_WARMUP;
                                if (rng.nextBoolean()) {
                                    long k = wl.nextKey(rng, last);
                                    place(mq, wl, k, numQueues);
                                    tracker.add(k);
                                } else {
                                    long k = mq.deleteMin(d, strat, syncPeriod, st);
                                    if (k != Long.MAX_VALUE) {
                                        last = k;
                                        if (record) tracker.observeAndRemove(k); else tracker.remove(k);
                                    }
                                }
                            }
                        }
                    }
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        try { done.await(); } catch (InterruptedException e) { return tracker.snapshot(); }
        return tracker.snapshot();
    }

    // ---------- measurement: concurrent throughput ----------

    static Thr throughput(int numQueues, int P, int d, MultiQueue.SampleStrategy strat,
                          int syncPeriod, int ringSize, Workloads wl) {
        runMq(numQueues, P, d, strat, syncPeriod, ringSize, wl, durationMs / 3, new AtomicLong()); // warmup
        long best = 0; double lfPct = 0;
        for (int r = 0; r < repeats; r++) {
            AtomicLong lf = new AtomicLong();
            long ops = runMq(numQueues, P, d, strat, syncPeriod, ringSize, wl, durationMs, lf);
            long thr = ops * 1000L / durationMs;
            if (thr > best) { best = thr; lfPct = 100.0 * lf.get() / Math.max(1, ops); }
        }
        return new Thr(best, lfPct);
    }

    static long runMq(final int numQueues, final int P, final int d,
                      final MultiQueue.SampleStrategy strat, final int syncPeriod, final int ringSize,
                      final Workloads wl, final long dur, final AtomicLong lockFailsOut) {
        final MultiQueue mq = new MultiQueue(numQueues);
        int prefill = 2000 + 200 * numQueues;
        ThreadLocalRandom rng0 = ThreadLocalRandom.current();
        long last0 = 0;
        for (int i = 0; i < prefill; i++) { long k = wl.nextKey(rng0, last0); place(mq, wl, k, numQueues); }

        final AtomicLong totalOps = new AtomicLong();
        final AtomicLong checksum = new AtomicLong();
        final long end = System.currentTimeMillis() + dur;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(P);
        for (int t = 0; t < P; t++) {
            new Thread(new Runnable() {
                public void run() {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    MultiQueue.ThreadSampleState st = mq.newThreadState(ringSize); // also tallies lock-fails
                    long ops = 0, sum = 0, last = 0;
                    try { start.await(); } catch (InterruptedException e) { return; }
                    while (System.currentTimeMillis() < end) {
                        for (int b = 0; b < 64; b++) {
                            if (rng.nextBoolean()) {
                                long k = wl.nextKey(rng, last);
                                place(mq, wl, k, numQueues);
                            } else {
                                long k = mq.deleteMin(d, strat, syncPeriod, st);
                                if (k != Long.MAX_VALUE) { last = k; sum ^= k; }
                            }
                            ops++;
                        }
                    }
                    totalOps.addAndGet(ops);
                    checksum.addAndGet(sum);
                    lockFailsOut.addAndGet(st.lockFails);
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        try { done.await(); } catch (InterruptedException e) { return 0; }
        if (checksum.get() == Long.MIN_VALUE) System.out.print(""); // keep JIT from eliding sum
        return totalOps.get();
    }

    static long strictThroughput(int P) {
        runStrict(P, durationMs / 3);
        long best = 0;
        for (int r = 0; r < repeats; r++) {
            long ops = runStrict(P, durationMs);
            long thr = ops * 1000L / durationMs;
            if (thr > best) best = thr;
        }
        return best;
    }

    static long runStrict(final int P, final long dur) {
        final StrictPQ pq = new StrictPQ(4096);
        for (int i = 0; i < 4000; i++) pq.insert(ThreadLocalRandom.current().nextInt(Workloads.UNIVERSE));
        final AtomicLong totalOps = new AtomicLong();
        final AtomicLong checksum = new AtomicLong();
        final long end = System.currentTimeMillis() + dur;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(P);
        for (int t = 0; t < P; t++) {
            new Thread(new Runnable() {
                public void run() {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    long ops = 0, sum = 0;
                    try { start.await(); } catch (InterruptedException e) { return; }
                    while (System.currentTimeMillis() < end) {
                        for (int b = 0; b < 64; b++) {
                            if (rng.nextBoolean()) pq.insert(rng.nextInt(Workloads.UNIVERSE));
                            else { long k = pq.deleteMin(); if (k != Long.MIN_VALUE) sum ^= k; }
                            ops++;
                        }
                    }
                    totalOps.addAndGet(ops);
                    checksum.addAndGet(sum);
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        try { done.await(); } catch (InterruptedException e) { return 0; }
        if (checksum.get() == Long.MIN_VALUE) System.out.print("");
        return totalOps.get();
    }

    // ---------- output ----------

    static class Thr {
        final long thr; final double lockFailPct;
        Thr(long thr, double lockFailPct) { this.thr = thr; this.lockFailPct = lockFailPct; }
    }

    // quality-only row, no throughput (sequential or concurrent rank error).
    static void qRow(String exp, String strat, Workloads wl, int d, int c, int P, int syncPeriod,
                     int ringSize, RankError.RankStats s) {
        row(exp, strat, wl, d, c, P, syncPeriod, ringSize, 0, 0, s);
    }

    static void row(String exp, String strat, Workloads wl, int d, int c, int P, int syncPeriod,
                    int ringSize, long thr, double lockFailPct, RankError.RankStats s) {
        String sp = (syncPeriod == MultiQueue.NEVER_SYNC) ? "never" : Integer.toString(syncPeriod);
        System.out.println(exp + "," + strat + "," + wl + "," + d + "," + c + "," + P + "," + sp + ","
                + ringSize + "," + thr + "," + String.format("%.3f", lockFailPct) + ","
                + String.format("%.2f", s.mean) + "," + s.p99 + "," + s.max + "," + s.count);
    }
}
