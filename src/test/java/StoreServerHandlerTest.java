import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StoreServerHandler için Netty EmbeddedChannel tabanlı entegrasyon testleri.
 *
 * Gerçek TCP bağlantısı olmadan tüm pipeline'ı (framing → decode → handler → encode)
 * uçtan uca test eder.
 *
 * Not: EmbeddedChannel.readOutbound() StringEncoder çıktısını ByteBuf olarak döner;
 * bu yüzden ByteBuf → String dönüşümü yapan bir yardımcı metot kullanılır.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoreServerHandlerTest {

    private InMemoryStore store;
    private EmbeddedChannel channel;

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        store = new InMemoryStore(10);
        StoreServerHandler handler = new StoreServerHandler(store, true, null);

        channel = new EmbeddedChannel(
                new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()),
                new StringDecoder(CharsetUtil.UTF_8),
                new StringEncoder(CharsetUtil.UTF_8),
                handler
        );
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    // -------------------------------------------------------------------------
    // Yardımcı: komut gönder, string yanıt döndür
    // -------------------------------------------------------------------------

    /**
     * Pipeline'a bir komut satırı yazar ve outbound'daki ByteBuf'ı String'e çevirir.
     */
    private String send(String command) {
        byte[] bytes = (command + "\n").getBytes(CharsetUtil.UTF_8);
        channel.writeInbound(Unpooled.copiedBuffer(bytes));
        channel.runPendingTasks();

        // StringEncoder ByteBuf yazar; bunu String'e çeviriyoruz
        ByteBuf outBuf = channel.readOutbound();
        if (outBuf == null) return "";
        try {
            return outBuf.toString(CharsetUtil.UTF_8);
        } finally {
            outBuf.release();
        }
    }

    // =========================================================================
    // 1. Temel komutlar
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("SET → OK döner")
    void testSetReturnsOk() {
        assertEquals("OK\n", send("SET foo bar"));
    }

    @Test
    @Order(2)
    @DisplayName("GET mevcut anahtar → değer döner")
    void testGetExistingKey() {
        send("SET foo bar");
        assertEquals("bar\n", send("GET foo"));
    }

    @Test
    @Order(3)
    @DisplayName("GET bilinmeyen anahtar → NIL döner")
    void testGetMissingKey() {
        assertEquals("NIL\n", send("GET ghost"));
    }

    @Test
    @Order(4)
    @DisplayName("DEL mevcut anahtar → 1 döner")
    void testDelExistingKey() {
        send("SET foo bar");
        assertEquals("1\n", send("DEL foo"));
        assertEquals("NIL\n", send("GET foo"));
    }

    @Test
    @Order(5)
    @DisplayName("DEL bilinmeyen anahtar → 0 döner")
    void testDelMissingKey() {
        assertEquals("0\n", send("DEL ghost"));
    }

    @Test
    @Order(6)
    @DisplayName("SIZE → sayı döner")
    void testSize() {
        send("SET a 1");
        send("SET b 2");
        assertEquals("2\n", send("SIZE"));
    }

    @Test
    @Order(7)
    @DisplayName("PING → PONG döner")
    void testPing() {
        assertEquals("PONG\n", send("PING"));
    }

    @Test
    @Order(8)
    @DisplayName("STATS → metrik satırı döner")
    void testStats() {
        send("SET x 1");
        send("GET x");      // hit
        send("GET ghost");  // miss
        String stats = send("STATS");
        assertTrue(stats.contains("hits=1"),   "hits sayacı 1 olmalı: " + stats);
        assertTrue(stats.contains("misses=1"), "misses sayacı 1 olmalı: " + stats);
    }

    @Test
    @Order(9)
    @DisplayName("Bilinmeyen komut → ERR döner")
    void testUnknownCommand() {
        String resp = send("FOOBAR");
        assertTrue(resp.startsWith("ERR Unknown command"), "Beklenen ERR: " + resp);
    }

    // =========================================================================
    // 2. Syntax hataları
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("SET eksik argüman → ERR Syntax döner")
    void testSetMissingArgs() {
        String resp = send("SET onlykey");
        assertTrue(resp.startsWith("ERR Syntax"), "Beklenen ERR Syntax: " + resp);
    }

    @Test
    @Order(11)
    @DisplayName("GET eksik argüman → ERR Syntax döner")
    void testGetMissingArgs() {
        String resp = send("GET");
        assertTrue(resp.startsWith("ERR Syntax"), "Beklenen ERR Syntax: " + resp);
    }

    // =========================================================================
    // 3. Replikasyon döngü koruması
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("REPL SET: veri store'a yazılır")
    void testReplPrefixWritesToStore() {
        send("REPL SET repl_key repl_val");
        assertEquals("repl_val\n", send("GET repl_key"),
                "REPL komutu store'a yazılmış olmalı");
    }

    @Test
    @Order(21)
    @DisplayName("Boş satır: istisna fırlatılmaz")
    void testEmptyLineIgnored() {
        // Boş satır pipeline tarafından DROP edilir (DelimiterBasedFrameDecoder)
        assertDoesNotThrow(() -> {
            channel.writeInbound(Unpooled.copiedBuffer("\n".getBytes(CharsetUtil.UTF_8)));
            channel.runPendingTasks();
        });
    }

    // =========================================================================
    // 4. Değer içindeki boşluklar
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("SET: değer içindeki boşluklar korunur")
    void testSetValueWithSpaces() {
        send("SET msg Hello World 2024");
        assertEquals("Hello World 2024\n", send("GET msg"));
    }

    // =========================================================================
    // 5. Case insensitivity
    // =========================================================================

    @Test
    @Order(31)
    @DisplayName("Komutlar büyük/küçük harf duyarsız")
    void testCaseInsensitiveCommands() {
        assertEquals("OK\n",    send("set lower upper"));
        assertEquals("upper\n", send("get lower"));
        // PING ayrı bir send çağrısı — önceki buffer'dan izole
        assertEquals("PONG\n",  send("PING"));
    }

}
