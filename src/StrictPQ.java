import java.util.concurrent.locks.ReentrantLock;

/**
 * Strict (exact) concurrent priority queue: one sequential heap guarded by a single lock.
 *
 * Baseline for comparing against the relaxed queue. Everything's serialized here, so
 * it won't scale past one thread doing useful work at a time.
 */
public class StrictPQ {
    private final SeqHeap heap;
    private final ReentrantLock lock;

    /**
     * Creates a new strict PQ with the given initial capacity.
     */
    public StrictPQ(int initialCapacity) {
        this.heap = new SeqHeap(initialCapacity);
        this.lock = new ReentrantLock();
    }

    /**
     * Inserts a key into the priority queue. Grabs the lock like every other op here.
     */
    public void insert(long key) {
        lock.lock();
        try {
            heap.insert(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes and returns the minimum key.
     * Returns Long.MIN_VALUE if empty. Thread-safe like the rest of these.
     */
    public long deleteMin() {
        lock.lock();
        try {
            if (heap.isEmpty()) {
                return Long.MIN_VALUE;
            }
            return heap.deleteMin();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in the queue.
     */
    public int size() {
        lock.lock();
        try {
            return heap.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns true if the queue is empty.
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return heap.isEmpty();
        } finally {
            lock.unlock();
        }
    }
}
