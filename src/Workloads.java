import java.util.concurrent.ThreadLocalRandom;

// Workload = next key + where it goes. MultiQueue's rank-error analysis assumes both
// that keys are random and that "each element is equally likely to be in any queue"
// (Williams, Sanders, ESA 2021) -- UNIFORM is exactly that regime. MONOTONE only messes
// with key order, placement's still uniform. SKEWED breaks the placement half of it.
public enum Workloads {
    // i.i.d. uniform keys, uniform-random placement -- the regime the analysis assumes.
    UNIFORM,

    // Dijkstra-like: each insert is slightly larger than this producer's last deletion.
    // Placement is still uniform-random, so this isolates the effect of key order alone.
    MONOTONE,

    // High-priority (small) keys are routed to a small "hot" subset of queues, modelling a
    // producer-consumer pattern where urgent work is produced by a few sources. Placement is
    // no longer uniform, so the global minima concentrate in few queues and uniform deleteMin
    // sampling frequently misses them.
    SKEWED;

    public static final int UNIVERSE = RankError.UNIVERSE;
    private static final int MONO_STEP_MAX = 8;
    private static final int HOT_KEY_THRESHOLD = UNIVERSE / 16;

    public long nextKey(ThreadLocalRandom rng, long lastDeleted) {
        switch (this) {
            case MONOTONE: {
                long k = lastDeleted + 1 + rng.nextInt(MONO_STEP_MAX);
                return k >= UNIVERSE ? UNIVERSE - 1 : k;
            }
            case UNIFORM:
            case SKEWED:
            default:
                return rng.nextInt(UNIVERSE);
        }
    }

    // Target queue for a key, or -1 to mean "uniform-random placement".
    public int placement(long key, int numQueues) {
        if (this != SKEWED || key >= HOT_KEY_THRESHOLD) return -1;
        int hotSetSize = Math.max(1, numQueues / 8);
        return (int) (key % hotSetSize);
    }
}
