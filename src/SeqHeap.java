/**
 * Sequential array-backed binary min-heap of primitive long keys.
 *
 * Not thread-safe, resizes the backing array when it fills up.
 * insert/deleteMin are O(log n). Used internally by both MultiQueue and StrictPQ.
 */
public class SeqHeap {
    private long[] heap;
    private int size;

    /**
     * Empty heap, given initial capacity.
     */
    public SeqHeap(int initialCap) {
        this.heap = new long[Math.max(16, initialCap)];
        this.size = 0;
    }

    /**
     * insert, O(log n)
     */
    public void insert(long key) {
        if (size == heap.length) {
            grow();
        }
        heap[size] = key;
        siftUp(size);
        size++;
    }

    /**
     * Pops and returns the min key, O(log n). Heap can't be empty when
     * this is called (throws if it is).
     */
    public long deleteMin() {
        if (size == 0) {
            throw new IllegalStateException("deleteMin on empty heap");
        }
        long min = heap[0];
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            siftDown(0);
        }
        return min;
    }

    /**
     * Min key without removing it. Long.MAX_VALUE if empty. O(1).
     */
    public long peekMin() {
        return size == 0 ? Long.MAX_VALUE : heap[0];
    }

    /**
     * Returns the number of elements in the heap.
     */
    public int size() {
        return size;
    }

    /**
     * Returns true if the heap is empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    // ========== Internal helpers ==========

    private void siftUp(int idx) {
        long key = heap[idx];
        while (idx > 0) {
            int parent = (idx - 1) / 2;
            if (heap[parent] <= key) {
                break;
            }
            heap[idx] = heap[parent];
            idx = parent;
        }
        heap[idx] = key;
    }

    private void siftDown(int idx) {
        long key = heap[idx];
        int half = size / 2;
        while (idx < half) {
            int left = 2 * idx + 1;
            int right = left + 1;
            int child = left;
            if (right < size && heap[right] < heap[left]) {
                child = right;
            }
            if (key <= heap[child]) {
                break;
            }
            heap[idx] = heap[child];
            idx = child;
        }
        heap[idx] = key;
    }

    private void grow() {
        int newCap = heap.length * 2;
        long[] newHeap = new long[newCap];
        System.arraycopy(heap, 0, newHeap, 0, size);
        heap = newHeap;
    }
}
