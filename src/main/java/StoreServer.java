import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavRedis ana sunucu sınıfı.
 *
 * Özellikler:
 *  - ServerConfig üzerinden tüm parametreleri alır (port, kapasite, rol, follower listesi)
 *  - ReplicationManager ile Leader→Follower replikasyonu yönetilir
 *  - IdleStateHandler + IdleConnectionHandler ile zombie bağlantılar temizlenir
 *  - SIGTERM / SIGINT sinyallerinde graceful shutdown çalışır
 *  - SO_BACKLOG, SO_KEEPALIVE, TCP_NODELAY seçenekleri açık (production default'ları)
 */
public class StoreServer {
    private static final Logger log = LoggerFactory.getLogger(StoreServer.class);

    private final ServerConfig       config;
    private final InMemoryStore      store;
    private final ReplicationManager replicationManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public StoreServer(ServerConfig config) {
        this.config = config;
        this.store  = new InMemoryStore(config.getCapacity());
        this.replicationManager = (config.isLeader() && !config.getFollowerPorts().isEmpty())
                ? new ReplicationManager(config.getFollowerPorts())
                : null;
    }

    public void start() throws InterruptedException {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();          // default: CPU çekirdeği × 2

        // Leader ise follower bağlantılarını başlat
        if (replicationManager != null) {
            replicationManager.connect();
        }

        // JVM kapatma sinyali için hook
        registerShutdownHook();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             // Sunucu soket seçenekleri
             .option(ChannelOption.SO_BACKLOG, 1024)
             // Her kabul edilen bağlantı için seçenekler
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();

                     // 1. Idle timeout: N saniye okuma yoksa bağlantı kapatılır
                     p.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                     p.addLast(new IdleConnectionHandler());

                     // 2. Framing: satır sonunu frame sınırı olarak kullan (max 8KB)
                     p.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));

                     // 3. String codec (UTF-8)
                     p.addLast(new StringDecoder(CharsetUtil.UTF_8));
                     p.addLast(new StringEncoder(CharsetUtil.UTF_8));

                     // 4. İş mantığı
                     p.addLast(new StoreServerHandler(store, config.isLeader(), replicationManager));
                 }
             });

            String role = config.isLeader() ? "LEADER" : "FOLLOWER";
            log.info("[{}] Sunucu :{} portunda başlatılıyor... (kapasite={})",
                     role, config.getPort(), config.getCapacity());

            ChannelFuture f = b.bind(config.getPort()).sync();
            log.info("[{}] Sunucu hazır — bağlantı bekleniyor.", role);

            f.channel().closeFuture().sync();

        } finally {
            shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Graceful shutdown
    // -------------------------------------------------------------------------

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Kapatma sinyali alındı, graceful shutdown başlatılıyor...");
            try {
                shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "javredis-shutdown-hook"));
    }

    private void shutdown() throws InterruptedException {
        // AtomicBoolean ile çift çağrıyı önle (shutdown hook + finally bloğu)
        if (!shuttingDown.compareAndSet(false, true)) return;

        log.info("Shutdown başladı...");
        if (replicationManager != null) replicationManager.shutdown();
        if (bossGroup  != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        log.info("Sunucu başarıyla kapatıldı.");
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        ServerConfig config = ServerConfig.load(args);
        new StoreServer(config).start();
    }
}