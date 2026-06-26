import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe LRU (Least Recently Used) in-memory key-value store.
 *
 * Veri yapısı:
 *   - HashMap    → O(1) anahtar erişimi
 *   - Doubly-linked list → LRU sırasını tutar (head = en yeni, tail = en eski)
 *
 * Thread-safety:
 *   Tüm okuma ve yazma işlemleri tek bir ReentrantLock altında gerçekleşir.
 *   HashMap erişimleri de lock içinde yapılır; böylece check-then-act
 *   race condition'ları tamamen ortadan kalkar.
 */
public class InMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);

    // -------------------------------------------------------------------------
    // Doubly-linked list node
    // -------------------------------------------------------------------------
    private static class Node {
        String key;
        String value;
        Node   prev;
        Node   next;

        Node(String key, String value) {
            this.key   = key;
            this.value = value;
        }
    }

    // -------------------------------------------------------------------------
    // Core state  (tümü lock ile korunur)
    // -------------------------------------------------------------------------
    private final int              capacity;
    private final HashMap<String, Node> map;   // ConcurrentHashMap kaldırıldı: lock yeterli
    private final Node             head;       // dummy head (en güncel yön)
    private final Node             tail;       // dummy tail (en eski yön)
    private final ReentrantLock    lock = new ReentrantLock();

    // -------------------------------------------------------------------------
    // Metrikler (AtomicLong — lock dışından da okunabilir)
    // -------------------------------------------------------------------------
    private final AtomicLong hitCount     = new AtomicLong();
    private final AtomicLong missCount    = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final AtomicLong totalSets    = new AtomicLong();
    private final AtomicLong totalDeletes = new AtomicLong();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public InMemoryStore(int capacity) {
        this.capacity = capacity;
        this.map      = new HashMap<>(capacity * 2);

        // Dummy sentinel node'lar — null kontrollerini ortadan kaldırır
        this.head      = new Node(null, null);
        this.tail      = new Node(null, null);
        head.next      = tail;
        tail.prev      = head;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Veri okuma — O(1).
     * Erişilen düğüm LRU listesinin başına (en güncel konuma) taşınır.
     *
     * @return değer, ya da anahtar bulunamazsa {@code null}
     */
    public String get(String key) {
        lock.lock();
        try {
            Node node = map.get(key);
            if (node == null) {
                missCount.incrementAndGet();
                return null;
            }
            hitCount.incrementAndGet();
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Veri yazma / güncelleme — O(1).
     * Anahtar zaten varsa değeri güncellenir; yoksa yeni düğüm eklenir.
     * Kapasite doluysa en eski düğüm (tail.prev) evict edilir.
     */
    public void set(String key, String value) {
        lock.lock();
        try {
            Node node = map.get(key);
            if (node != null) {
                // Güncelleme: değeri yaz, başa taşı
                node.value = value;
                moveToHead(node);
            } else {
                // Yeni kayıt: önce kapasite kontrolü
                if (map.size() >= capacity) {
                    Node lruNode = tail.prev;
                    removeNode(lruNode);
                    map.remove(lruNode.key);
                    evictionCount.incrementAndGet();
                    log.debug("[Eviction] En eski anahtar silindi: {}", lruNode.key);
                }
                Node newNode = new Node(key, value);
                addNode(newNode);
                map.put(key, newNode);
            }
            totalSets.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Anahtar silme — O(1).
     *
     * @return {@code true} anahtar bulunup silindiyse, {@code false} bulunamadıysa
     */
    public boolean delete(String key) {
        lock.lock();
        try {
            Node node = map.remove(key);
            if (node == null) return false;
            removeNode(node);
            totalDeletes.incrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Anlık kayıt sayısını döner.
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Anlık metrik görüntüsü döner (immutable snapshot).
     */
    public StoreStats getStats() {
        lock.lock();
        try {
            return new StoreStats(
                    hitCount.get(), missCount.get(),
                    evictionCount.get(), totalSets.get(),
                    totalDeletes.get(), map.size(), capacity
            );
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Doubly-linked list yardımcı metotları (lock altında çağrılmalıdır)
    // -------------------------------------------------------------------------

    /** Düğümü head'in hemen arkasına ekler (en güncel konum). */
    private void addNode(Node node) {
        node.prev       = head;
        node.next       = head.next;
        head.next.prev  = node;
        head.next       = node;
    }

    /** Düğümü listeden koparır. */
    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /** Düğümü mevcut yerinden söküp listenin başına taşır. */
    private void moveToHead(Node node) {
        removeNode(node);
        addNode(node);
    }
}