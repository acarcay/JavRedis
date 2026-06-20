public class Main {
    public static void main(String[] args) {
        // Maksimum 3 anahtar alabilen bir depo oluşturuyoruz
        InMemoryStore store = new InMemoryStore(3);

        System.out.println("--- Veriler Ekleniyor ---");
        store.set("user:1", "Acar");
        store.set("user:2", "Kerem");
        store.set("user:3", "Cigdem");

        // Şu an depo dolu (capacity: 3). user:1 en eski veri.

        System.out.println("\n--- Bir veriye erişiliyor (user:1 güncelleniyor) ---");
        // user:1'e eriştiğimiz için o artık "en yeni" konumuna geçecek, user:2 en eski olacak.
        System.out.println("Get user:1 -> " + store.get("user:1"));

        System.out.println("\n--- Kapasiteyi aşan yeni veri ekleniyor ---");
        // user:4 eklenince, en eski olan user:2'nin silinmesini bekliyoruz.
        store.set("user:4", "Yeni Veri");

        System.out.println("\n--- Durum Kontrolü ---");
        System.out.println("user:1 (Ayakta kalmalı): " + store.get("user:1"));
        System.out.println("user:2 (Silinmiş olmalı): " + store.get("user:2"));
        System.out.println("user:3 (Ayakta kalmalı): " + store.get("user:3"));
        System.out.println("user:4 (Ayakta kalmalı): " + store.get("user:4"));
    }
}