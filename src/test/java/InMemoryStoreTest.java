import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryStore için kapsamlı birim testleri.
 *
 * Test kategorileri:
 *  1. Temel GET / SET / DEL işlevselliği
 *  2. LRU eviction sırası
 *  3. Kapasite sınırı
 *  4. Metrik sayaçları (hit, miss, eviction)
 *  5. Eşzamanlı (concurrent) erişim — race condition kontrolü
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InMemoryStoreTest {

    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore(3); // küçük kapasite ile test
    }

    // =========================================================================
    // 1. Temel işlemler
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("SET → GET: değer doğru döner")
    void testSetAndGet() {
        store.set("k1", "v1");
        assertEquals("v1", store.get("k1"));
    }

    @Test
    @Order(2)
    @DisplayName("GET: bilinmeyen anahtar null döner")
    void testGetMissingKey() {
        assertNull(store.get("nonexistent"));
    }

    @Test
    @Order(3)
    @DisplayName("SET → SET: değer güncellenir")
    void testUpdateExistingKey() {
        store.set("k1", "v1");
        store.set("k1", "v2");
        assertEquals("v2", store.get("k1"));
        assertEquals(1, store.size()); // hâlâ tek kayıt
    }

    @Test
    @Order(4)
    @DisplayName("DEL: mevcut anahtar silinir, true döner")
    void testDeleteExistingKey() {
        store.set("k1", "v1");
        assertTrue(store.delete("k1"));
        assertNull(store.get("k1"));
        assertEquals(0, store.size());
    }

    @Test
    @Order(5)
    @DisplayName("DEL: mevcut olmayan anahtar false döner")
    void testDeleteMissingKey() {
        assertFalse(store.delete("ghost"));
    }

    @Test
    @Order(6)
    @DisplayName("SIZE: doğru boyut döner")
    void testSize() {
        assertEquals(0, store.size());
        store.set("a", "1");
        store.set("b", "2");
        assertEquals(2, store.size());
        store.delete("a");
        assertEquals(1, store.size());
    }

    // =========================================================================
    // 2. LRU eviction sırası
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("LRU: kapasite dolunca en eski kayıt silinir")
    void testLruEvictionBasic() {
        // Ekle: k1 (en eski) → k2 → k3 (en yeni)
        store.set("k1", "v1");
        store.set("k2", "v2");
        store.set("k3", "v3");

        // k4 eklenince k1 (en eski) silinmeli
        store.set("k4", "v4");

        assertNull(store.get("k1"),  "k1 evict edilmiş olmalı");
        assertNotNull(store.get("k2"), "k2 ayakta olmalı");
        assertNotNull(store.get("k3"), "k3 ayakta olmalı");
        assertNotNull(store.get("k4"), "k4 ayakta olmalı");
    }

    @Test
    @Order(11)
    @DisplayName("LRU: GET erişimi sıralamayı günceller")
    void testLruOrderUpdatedOnGet() {
        store.set("k1", "v1");
        store.set("k2", "v2");
        store.set("k3", "v3");

        // k1'e erişerek onu en yeni hale getir; artık k2 en eski
        store.get("k1");

        // k4 eklenince k2 (yeni en eski) silinmeli
        store.set("k4", "v4");

        assertNotNull(store.get("k1"), "k1 GET sonrası korunmalı");
        assertNull(store.get("k2"),    "k2 evict edilmiş olmalı");
        assertNotNull(store.get("k3"), "k3 korunmalı");
        assertNotNull(store.get("k4"), "k4 korunmalı");
    }

    @Test
    @Order(12)
    @DisplayName("LRU: SET güncellemesi sıralamayı günceller")
    void testLruOrderUpdatedOnSet() {
        store.set("k1", "v1");
        store.set("k2", "v2");
        store.set("k3", "v3");

        // k1'i güncelleyerek onu en yeni yap; k2 en eski konuma düşer
        store.set("k1", "updated");

        store.set("k4", "v4"); // k2 evict edilmeli

        assertEquals("updated", store.get("k1"), "k1 güncellenmiş değer");
        assertNull(store.get("k2"),    "k2 evict edilmiş olmalı");
    }

    // =========================================================================
    // 3. Metrik sayaçları
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("Metrikler: hit / miss / eviction sayaçları doğru")
    void testMetrics() {
        store.set("k1", "v1");
        store.set("k2", "v2");
        store.set("k3", "v3");

        store.get("k1");   // hit
        store.get("k1");   // hit
        store.get("k99");  // miss

        store.set("k4", "v4"); // eviction (k2 silinir)

        StoreStats stats = store.getStats();
        assertEquals(2, stats.getHits());
        assertEquals(1, stats.getMisses());
        assertEquals(1, stats.getEvictions());
        assertTrue(stats.hitRate() > 0.0);
    }

    @Test
    @Order(21)
    @DisplayName("Metrikler: hit rate başlangıçta 0.0")
    void testHitRateInitiallyZero() {
        assertEquals(0.0, store.getStats().hitRate(), 0.001);
    }

    // =========================================================================
    // 4. Eşzamanlı erişim (race condition kontrolü)
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Concurrent SET: 100 thread aynı anda yazar, kayıp yok")
    void testConcurrentSets() throws InterruptedException {
        InMemoryStore bigStore = new InMemoryStore(200);
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    bigStore.set("key-" + idx, "val-" + idx);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, bigStore.size(),
                "Tüm kayıtlar eviction olmadan yazılmış olmalı");
    }

    @Test
    @Order(31)
    @DisplayName("Concurrent GET+SET: eşzamanlı karışık işlemlerde istisna fırlatılmaz")
    void testConcurrentMixedOperations() throws InterruptedException {
        InMemoryStore smallStore = new InMemoryStore(10);
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            Future<?> f = executor.submit(() -> {
                smallStore.set("k" + (idx % 15), "v" + idx); // kasıtlı eviction
                smallStore.get("k" + (idx % 15));
                smallStore.delete("k" + (idx % 5));
            });
            futures.add(f);
        }

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get(5, TimeUnit.SECONDS),
                    "Eşzamanlı işlemlerde istisna oluşmamalı");
        }
        executor.shutdown();
    }
}
