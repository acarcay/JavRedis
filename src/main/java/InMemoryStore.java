import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class InMemoryStore {

    // LRU için Çift Yönlü Bağlı Liste (Doubly Linked List) Düğümü
    private static class Node {
        String key;
        String value;
        Node prev;
        Node next;

        Node(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity; // Depomuzun alabileceği maksimum anahtar sayısı
    private final ConcurrentHashMap<String, Node> map; // O(1) erişim için hızlı harita

    // Bağlı listenin başı (en güncel) ve sonu (en eski, ilk silinecek)
    private final Node head;
    private final Node tail;

    // Liste manipülasyonlarını korumak için kilit (Lock) mekanizması
    private final ReentrantLock lock = new ReentrantLock();

    public InMemoryStore(int capacity) {
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);

        // Dummy (Sahte) baş ve son düğümler oluşturarak edge case'leri (null kontrollerini) önlüyoruz
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Veri Okuma (GET) - O(1)
     */
    public String get(String key) {
        Node node = map.get(key);
        if (node == null) {
            return null; // Anahtar bulunamadı
        }

        // Veriye erişildiği için onu listenin en başına (en güncel konumuna) taşımalıyız
        lock.lock();
        try {
            moveToHead(node);
        } finally {
            lock.unlock();
        }

        return node.value;
    }

    /**
     * Veri Yazma / Güncelleme (SET) - O(1)
     */
    public void set(String key, String value) {
        Node node = map.get(key);

        if (node != null) {
            // Anahtar zaten var, değerini güncelle ve başa taşı
            lock.lock();
            try {
                node.value = value;
                moveToHead(node);
            } finally {
                lock.unlock();
            }
        } else {
            // Yeni bir veri ekleniyor
            Node newNode = new Node(key, value);

            lock.lock();
            try {
                // Kapasite dolduysa en eski veriyi (tail'den bir öncekini) sil
                if (map.size() >= capacity) {
                    Node lruNode = tail.prev;
                    removeNode(lruNode);
                    map.remove(lruNode.key);
                    System.out.println("[Eviction] Kapasite doldu. En eski anahtar silindi: " + lruNode.key);
                }

                // Yeni düğümü listeye ekle ve haritaya koy
                addNode(newNode);
                map.put(key, newNode);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Mevcut boyutu döner
     */
    public int size() {
        return map.size();
    }

    // --- Bağlı Liste Yardımcı Metotları (Lock altında çağrılmalıdır) ---

    private void addNode(Node node) {
        // Düğümü her zaman head'in hemen arkasına ekler (En güncel konum)
        node.prev = head;
        node.next = head.next;

        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        // Düğümü listeden koparır
        Node prevNode = node.prev;
        Node nextNode = node.next;

        prevNode.next = nextNode;
        nextNode.prev = prevNode;
    }

    private void moveToHead(Node node) {
        // Düğümü mevcut yerinden söküp başa taşır
        removeNode(node);
        addNode(node);
    }
}