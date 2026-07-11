/**
 * Exact rank-error measurement over a fixed dense key universe [0, UNIVERSE).
 *
 * The MultiQueue rank-error analyses (Rihani et al., SPAA 2015; Walzer & Williams,
 * ESA 2025) are stated for the sequential execution of the data structure
 * (Williams et al.: "we only consider the sequential execution of operations").
 * So to match that assumption, this drives the actual MultiQueue with a single
 * thread and keeps, in lock-step, an exact multiset of the keys currently
 * present. For each deleteMin that returns key x, the rank error is the number of
 * present keys strictly smaller than x:
 *
 *     rankError(x) = |{ y currently in the queue : y < x }|
 *
 * (definition from Rihani et al. / Williams et al.)
 *
 * The multiset is a Fenwick tree (Binary Indexed Tree) over the dense universe,
 * giving O(log UNIVERSE) insert/remove/prefix-count. Don't need coordinate
 * compression or timestamp sorting since keys live in a fixed integer range and
 * the driver is sequential, which kills most of the measurement noise. So the
 * numbers can be compared directly against the closed-form prediction (5/6)*numQueues.
 */
public class RankError {

    /** Keys must lie in [0, UNIVERSE). */
    public static final int UNIVERSE = 1 << 20; // 1,048,576

    // Fenwick tree (1-indexed), count of each present key.
    private final long[] bit;
    private long present; // total elements currently in the multiset

    // running rank-error stats
    private long count;          // # of deleteMin observations recorded
    private long sum;            // sum of rank errors (for mean)
    private long max;
    private final long[] hist;   // histogram of rank errors for percentile, capped
    private static final int HIST_CAP = 1 << 16; // ranks >= this go in overflow bucket
    private long histOverflow;

    public RankError() {
        this.bit = new long[UNIVERSE + 1];
        this.present = 0;
        this.count = 0;
        this.sum = 0;
        this.max = 0;
        this.hist = new long[HIST_CAP];
        this.histOverflow = 0;
    }

    /** Adds one occurrence of key to the present multiset. */
    public void add(long key) {
        int i = clamp(key) + 1; // 1-indexed
        while (i <= UNIVERSE) {
            bit[i] += 1;
            i += i & (-i);
        }
        present++;
    }

    /** Removes one occurrence of key from the present multiset. */
    public void remove(long key) {
        int i = clamp(key) + 1;
        while (i <= UNIVERSE) {
            bit[i] -= 1;
            i += i & (-i);
        }
        present--;
    }

    /** Number of present keys strictly smaller than key. */
    public long countLess(long key) {
        int k = clamp(key); // prefix sum over [0, k-1] == query(k-1), 0-indexed
        int i = k; // indices 1..k correspond to keys 0..k-1
        long s = 0;
        while (i > 0) {
            s += bit[i];
            i -= i & (-i);
        }
        return s;
    }

    public long present() {
        return present;
    }

    /**
     * Records the rank error of a deleteMin that returned key x.
     * (calls countLess(x) before removing x, does both in one shot)
     */
    public void observeAndRemove(long x) {
        long re = countLess(x);
        sum += re;
        if (re > max) max = re;
        if (re < HIST_CAP) hist[(int) re]++; else histOverflow++;
        count++;
        remove(x);
    }

    // ---- statistics ----

    public long count() { return count; }

    public double mean() { return count == 0 ? 0.0 : (double) sum / count; }

    public long max() { return max; }

    /** Approximate q-quantile (e.g. q=0.99) of the recorded rank errors. */
    public long quantile(double q) {
        if (count == 0) return 0;
        long target = (long) Math.ceil(q * count);
        if (target < 1) target = 1;
        long cum = 0;
        for (int r = 0; r < HIST_CAP; r++) {
            cum += hist[r];
            if (cum >= target) return r;
        }
        return max; // fell into overflow bucket
    }

    private static int clamp(long key) {
        if (key < 0) return 0;
        if (key >= UNIVERSE) return UNIVERSE - 1;
        return (int) key;
    }

    /** Immutable snapshot of rank-error statistics for printing. */
    public static class RankStats {
        public final double mean;
        public final long p99;
        public final long max;
        public final long count;

        public RankStats(double mean, long p99, long max, long count) {
            this.mean = mean;
            this.p99 = p99;
            this.max = max;
            this.count = count;
        }
    }

    public RankStats snapshot() {
        return new RankStats(mean(), quantile(0.99), max(), count());
    }
}
