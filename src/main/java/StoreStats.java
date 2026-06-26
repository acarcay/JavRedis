/**
 * InMemoryStore'un anlık metrik görüntüsü.
 * Thread-safe: immutable veri transfer objesi.
 */
public class StoreStats {

    private final long hits;
    private final long misses;
    private final long evictions;
    private final long totalSets;
    private final long totalDeletes;
    private final int  currentSize;
    private final int  capacity;

    public StoreStats(long hits, long misses, long evictions,
                      long totalSets, long totalDeletes,
                      int currentSize, int capacity) {
        this.hits         = hits;
        this.misses       = misses;
        this.evictions    = evictions;
        this.totalSets    = totalSets;
        this.totalDeletes = totalDeletes;
        this.currentSize  = currentSize;
        this.capacity     = capacity;
    }

    public long getHits()         { return hits; }
    public long getMisses()       { return misses; }
    public long getEvictions()    { return evictions; }
    public long getTotalSets()    { return totalSets; }
    public long getTotalDeletes() { return totalDeletes; }
    public int  getCurrentSize()  { return currentSize; }
    public int  getCapacity()     { return capacity; }

    /** GET isteklerindeki isabet oranı (0.0 – 100.0) */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total * 100.0;
    }

    @Override
    public String toString() {
        return String.format(
            "hits=%d misses=%d hit_rate=%.1f%% evictions=%d sets=%d deletes=%d size=%d/%d",
            hits, misses, hitRate(), evictions, totalSets, totalDeletes, currentSize, capacity
        );
    }
}
