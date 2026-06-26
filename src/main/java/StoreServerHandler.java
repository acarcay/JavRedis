import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gelen istemci komutlarını işleyen Netty handler.
 *
 * Desteklenen komutlar:
 *   SET  <key> <value>  → değer yazar, OK döner
 *   GET  <key>          → değer döner, yoksa NIL
 *   DEL  <key>          → siler; 1 (başarılı) veya 0 (bulunamadı)
 *   SIZE                → mevcut kayıt sayısı
 *   STATS               → hit/miss/eviction istatistikleri
 *   PING                → PONG (heartbeat / canlılık kontrolü)
 *
 * Replikasyon döngü koruması:
 *   Follower'a gelen replikasyon mesajları "REPL SET key value\n" öneki taşır.
 *   Handler bu öneki görünce komutu kendi store'una yazar fakat tekrar replike
 *   etmez — bu sayede Leader → Follower → Leader döngüsü imkânsız hale gelir.
 *
 * Thread-safety:
 *   Handler @Sharable değildir; her kanal kendi handler instance'ına sahiptir.
 *   InMemoryStore zaten thread-safe olduğundan ek kilit gerekmez.
 */
public class StoreServerHandler extends SimpleChannelInboundHandler<String> {
    private static final Logger log = LoggerFactory.getLogger(StoreServerHandler.class);

    private static final String REPL_PREFIX = "REPL ";

    private final InMemoryStore      store;
    private final boolean            isLeader;
    private final ReplicationManager replicationManager; // null → follower ya da test

    public StoreServerHandler(InMemoryStore store,
                              boolean isLeader,
                              ReplicationManager replicationManager) {
        this.store              = store;
        this.isLeader           = isLeader;
        this.replicationManager = replicationManager;
    }

    // -------------------------------------------------------------------------
    // Komut işleme
    // -------------------------------------------------------------------------

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        String line = msg.trim();
        if (line.isEmpty()) return;

        // Replikasyon döngü koruması: "REPL " öneki varsa soy ve bir daha replike etme
        boolean isReplication = line.startsWith(REPL_PREFIX);
        if (isReplication) {
            line = line.substring(REPL_PREFIX.length());
        }

        String[] tokens  = line.split("\\s+", 3);
        String   command = tokens[0].toUpperCase();
        String   response;

        switch (command) {

            case "SET" -> {
                if (tokens.length < 3) {
                    response = "ERR Syntax: SET <key> <value>\n";
                } else {
                    String key   = tokens[1];
                    String value = tokens[2];
                    store.set(key, value);
                    response = "OK\n";

                    // Sadece Leader, replikasyon olmayan komutları follower'lara yayar
                    if (isLeader && !isReplication && replicationManager != null) {
                        replicationManager.replicate(key, value);
                    }
                }
            }

            case "GET" -> {
                if (tokens.length < 2) {
                    response = "ERR Syntax: GET <key>\n";
                } else {
                    String value = store.get(tokens[1]);
                    response = (value == null) ? "NIL\n" : value + "\n";
                }
            }

            case "DEL" -> {
                if (tokens.length < 2) {
                    response = "ERR Syntax: DEL <key>\n";
                } else {
                    response = store.delete(tokens[1]) ? "1\n" : "0\n";
                }
            }

            case "SIZE"  -> response = store.size() + "\n";

            case "STATS" -> response = store.getStats().toString() + "\n";

            case "PING"  -> response = "PONG\n";

            default      -> response = "ERR Unknown command: " + command + "\n";
        }

        ctx.writeAndFlush(response);
    }

    // -------------------------------------------------------------------------
    // Kanal yaşam döngüsü olayları
    // -------------------------------------------------------------------------

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("Client bağlandı: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("Client ayrıldı: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Handler hatası [{}]: {}", ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}