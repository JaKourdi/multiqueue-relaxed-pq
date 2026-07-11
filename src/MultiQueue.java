import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

// Relaxed concurrent priority queue (a MultiQueue).
//   - structure + power-of-two-choices deleteMin: Rihani, Sanders, Dementiev, SPAA 2015.
//   - rank-error / delay quality criteria and the c*p sizing: Williams, Sanders, ESA 2021.
//   - exact long-term rank error E ~ (5/6)*numQueues for d=2: Walzer, Williams, ESA 2025.
//
// numQueues = c*p internal sequential heaps, each behind its own lock. Every op uses
// tryLock and resamples on failure instead of blocking, so this is lock-based but
// probabilistically wait-free (not lock-free).
//
// deleteMin has three sampling strategies:
//   UNIFORM - d uniform-random candidates, delete the smaller cached min. The baseline.
//   STALE   - d-1 uniform-random candidates plus one "stalest" candidate: the queue this
//             thread has gone longest without sampling. I picked this as my Part-3
//             improvement, advisor-approved: bias sampling toward queues nobody has
//             touched in a while. syncPeriod controls how the staleness signal gets
//             shared - a global per-queue tracker would just recreate the bottleneck
//             we're trying to get away from, so each thread keeps its own view and only
//             reconciles with a shared array every syncPeriod ops:
//               syncPeriod == NEVER_SYNC -> pure thread-local staleness, no shared state
//               syncPeriod == k          -> reconcile with the shared array every k ops
//               syncPeriod == 1          -> reconcile every op (fully global, most contended)
//   WINNER  - d-1 uniform-random candidates plus one "remembered winner" pulled from a
//             tiny thread-local ring of recently-winning queue indices. Built this after
//             STALE turned out weak in testing: it's power-of-two-choices with memory
//             (Mitzenmacher, Prabhakar, Shah, FOCS 2002), moved over to the deletion side.
//             Unlike STALE, which biases toward neglected queues with no relation to
//             where the small elements actually sit, this biases toward queues that have
//             empirically held small elements - the hot set under non-uniform placement.
//             Ended up beating both other strategies in the benchmarks. No shared state
//             at all; re-exploration period reuses the syncPeriod arg:
//               syncPeriod == NEVER_SYNC -> pure greedy, always consult memory
//               syncPeriod == k          -> force a uniform "explore" draw with prob 1/k
//             same idea as the reconciliation above, just thread-local - keeps a herd
//             from forming without ever touching shared memory.
public class MultiQueue {

    public enum SampleStrategy { UNIFORM, STALE, WINNER }

    public static final int NEVER_SYNC = Integer.MAX_VALUE;
    static final int WIN_RING = 4; // default remembered-winner ring size

    private final QueueWrapper[] queues;
    private final int numQueues;

    // Shared staleness signal, used by STALE when syncPeriod is finite. Per queue, the latest
    // logical sample-time any thread has pushed. Only touched every syncPeriod ops per thread.
    private final AtomicLongArray globalLastSampled;

    public MultiQueue(int numQueues) {
        this.numQueues = numQueues;
        this.queues = new QueueWrapper[numQueues];
        for (int i = 0; i < numQueues; i++) queues[i] = new QueueWrapper();
        this.globalLastSampled = new AtomicLongArray(numQueues);
    }

    public int numQueues() { return numQueues; }

    // Standard insert: pick a uniform-random queue, tryLock, resample on failure.
    public void insert(long key) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (true) {
            int idx = rng.nextInt(numQueues);
            if (tryInsert(idx, key)) return;
        }
    }

    // Affinity insert: try queueIdx first, fall back to uniform random if it's locked so we
    // still don't block. Lets a producer bias its own inserts, which breaks the uniform-
    // placement assumption the analysis relies on - useful for testing that case.
    public void insertAt(int queueIdx, long key) {
        if (tryInsert(queueIdx, key)) return;
        insert(key);
    }

    private boolean tryInsert(int idx, long key) {
        QueueWrapper qw = queues[idx];
        if (qw.lock.tryLock()) {
            try {
                qw.heap.insert(key);
                qw.cachedMin = qw.heap.peekMin();
                return true;
            } finally {
                qw.lock.unlock();
            }
        }
        return false;
    }

    public long deleteMin() {
        return deleteMin(2, SampleStrategy.UNIFORM, NEVER_SYNC, null);
    }

    // Returns the deleted key, or Long.MAX_VALUE if every sampled candidate was empty.
    //
    // All three strategies share the same control flow: build d candidate indices, read their
    // cached minima (lock-free), tryLock the smallest and pop. STALE and WINNER swap one of
    // the d uniform candidates for a non-uniform "hint" (the stalest queue, or a remembered
    // winner). The hint is just a suggestion though - we always re-check under the lock before
    // trusting it, so a bad hint only costs us a wasted sample, never correctness.
    public long deleteMin(int d, SampleStrategy strategy, int syncPeriod, ThreadSampleState st) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (true) {
            int hint = -1;
            if (strategy == SampleStrategy.STALE) {
                if (syncPeriod != NEVER_SYNC) st.maybeSync(globalLastSampled, syncPeriod);
                hint = st.findStalest();
            } else if (strategy == SampleStrategy.WINNER) {
                hint = st.pickWinner(rng, syncPeriod); // remembered winner, or -1 to force explore
            }

            int bestIdx = -1;
            long bestMin = Long.MAX_VALUE;
            int randomPicks = (hint >= 0) ? d - 1 : d;
            for (int i = 0; i < randomPicks; i++) {
                int idx = rng.nextInt(numQueues);
                long m = queues[idx].cachedMin;
                if (m < bestMin) { bestMin = m; bestIdx = idx; }
                if (strategy == SampleStrategy.STALE) st.markSampled(idx);
            }
            if (hint >= 0) {
                long m = queues[hint].cachedMin;
                if (m < bestMin) { bestMin = m; bestIdx = hint; }
                if (strategy == SampleStrategy.STALE) st.markSampled(hint);
            }

            if (bestIdx == -1 || bestMin == Long.MAX_VALUE) return Long.MAX_VALUE;

            QueueWrapper qw = queues[bestIdx];
            if (qw.lock.tryLock()) {
                try {
                    if (qw.heap.isEmpty()) {
                        qw.cachedMin = Long.MAX_VALUE;
                        if (strategy == SampleStrategy.WINNER) st.evictWinner(bestIdx, rng);
                        continue;
                    }
                    long key = qw.heap.deleteMin();
                    qw.cachedMin = qw.heap.peekMin();
                    if (strategy == SampleStrategy.WINNER) st.recordWinner(bestIdx);
                    return key;
                } finally {
                    qw.lock.unlock();
                }
            }
            // lock was contended: drop it from the remembered set so a herd disperses, then resample
            if (st != null) st.lockFails++;
            if (strategy == SampleStrategy.WINNER) st.evictWinner(bestIdx, rng);
        }
    }

    // Per-thread sampling state for STALE and WINNER. One instance per worker thread, so the
    // hot path never touches shared memory.
    public static class ThreadSampleState {
        private final int numQueues;

        // STALE: thread-local "least-recently-sampled" view
        private final long[] lastSampled; // logical time this thread last sampled queue i
        private long clock;
        private long opsSinceSync;

        // WINNER: thread-local memory of recently-winning queues, no shared state
        private final int[] winRing;      // recently-winning queue indices
        private int ringHand;             // round-robin write cursor, so old winners decay out

        // diagnostic only, read after the run is done
        long lockFails;

        ThreadSampleState(int numQueues, int ringSize) {
            this.numQueues = numQueues;
            this.lastSampled = new long[numQueues];
            this.winRing = new int[Math.max(1, ringSize)];
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < winRing.length; i++) winRing[i] = rng.nextInt(numQueues);
        }

        // STALE helpers
        void markSampled(int idx) { lastSampled[idx] = ++clock; }

        int findStalest() {
            int best = 0;
            long oldest = lastSampled[0];
            for (int i = 1; i < lastSampled.length; i++) {
                if (lastSampled[i] < oldest) { oldest = lastSampled[i]; best = i; }
            }
            return best;
        }

        // Reconcile with the shared array once every syncPeriod ops instead of every op, so it
        // doesn't become a bottleneck: pull in anyone else's more-recent samples (so we stop
        // re-picking queues someone else already covered) and push out our own.
        void maybeSync(AtomicLongArray global, int syncPeriod) {
            if (++opsSinceSync < syncPeriod) return;
            opsSinceSync = 0;
            for (int i = 0; i < lastSampled.length; i++) {
                long mine = lastSampled[i];
                long g = global.accumulateAndGet(i, mine, Math::max);
                if (g > mine) lastSampled[i] = g;
            }
        }

        // WINNER helpers, all purely thread-local

        // Returns a remembered winner to use as one candidate, or -1 to force a pure-uniform
        // draw (exploration). explorePeriod == NEVER_SYNC means never explore, i.e. pure greedy.
        int pickWinner(ThreadLocalRandom rng, int explorePeriod) {
            if (explorePeriod != NEVER_SYNC && rng.nextInt(explorePeriod) == 0) return -1;
            return winRing[rng.nextInt(winRing.length)];
        }

        // Remember the queue we just popped from - it held a small element. Overwrites the
        // oldest ring slot, so old winners decay out on their own without needing timestamps.
        void recordWinner(int idx) {
            winRing[ringHand] = idx;
            if (++ringHand == winRing.length) ringHand = 0;
        }

        // Drop a remembered queue that turned out contended or empty, replace it with a fresh
        // random index. Keeps a herd from staying latched onto one hot queue.
        void evictWinner(int idx, ThreadLocalRandom rng) {
            for (int i = 0; i < winRing.length; i++) {
                if (winRing[i] == idx) winRing[i] = rng.nextInt(numQueues);
            }
        }
    }

    public ThreadSampleState newThreadState() { return new ThreadSampleState(numQueues, WIN_RING); }

    public ThreadSampleState newThreadState(int ringSize) {
        return new ThreadSampleState(numQueues, ringSize);
    }

    private static class QueueWrapper {
        @SuppressWarnings("unused")
        private long p1, p2, p3, p4, p5, p6, p7; // padding, keeps this off neighboring cache lines
        private final SeqHeap heap = new SeqHeap(64);
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long cachedMin = Long.MAX_VALUE;
        @SuppressWarnings("unused")
        private long q1, q2, q3, q4, q5, q6, q7;
    }
}
