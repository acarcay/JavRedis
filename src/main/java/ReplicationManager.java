import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader sunucunun Follower'lara veri replike etmesini yöneten sınıf.
 *
 * Temel özellikler:
 *  - Her Follower için kalıcı bir Netty Channel tutulur (bağlantı havuzu).
 *  - Bağlantı koparsa otomatik yeniden bağlanma (5s backoff) devreye girer.
 *  - Replikasyon "REPL SET key value\n" prefix'i ile gönderilir; Follower bu
 *    prefix'i görünce veriyi kendi store'una yazar fakat tekrar replike etmez
 *    (sonsuz döngü koruması).
 */
public class ReplicationManager {
    private static final Logger log = LoggerFactory.getLogger(ReplicationManager.class);

    private static final long RECONNECT_DELAY_SECONDS = 5;

    private final List<Integer>                    followerPorts;
    private final EventLoopGroup                   group;
    private final Bootstrap                        bootstrap;
    private final ConcurrentHashMap<Integer, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean                    running   = new AtomicBoolean(true);

    public ReplicationManager(List<Integer> followerPorts) {
        this.followerPorts = followerPorts;

        // Replikasyon için küçük, ayrılmış bir thread havuzu
        this.group = new NioEventLoopGroup(2);

        this.bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
                    }
                });
    }

    /**
     * Tüm Follower'lara ilk bağlantıyı başlatır.
     * Sunucu başladığında bir kez çağrılır.
     */
    public void connect() {
        for (int port : followerPorts) {
            connectToFollower(port);
        }
    }

    /**
     * Bir Follower'a bağlan; başarısız olursa {@link #RECONNECT_DELAY_SECONDS} sonra tekrar dener.
     */
    private void connectToFollower(int port) {
        if (!running.get()) return;

        bootstrap.connect("localhost", port).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                Channel ch = f.channel();
                channels.put(port, ch);
                log.info("[Replication] Follower :{} bağlantısı kuruldu", port);

                // Kanal kapanınca otomatik yeniden bağlan
                ch.closeFuture().addListener(closeFut -> {
                    channels.remove(port);
                    if (running.get()) {
                        log.warn("[Replication] Follower :{} bağlantısı koptu, {}s sonra yeniden deneniyor...",
                                 port, RECONNECT_DELAY_SECONDS);
                        scheduleReconnect(port);
                    }
                });
            } else {
                log.warn("[Replication] Follower :{} bağlantısı kurulamadı ({}), {}s sonra yeniden deneniyor...",
                         port, f.cause().getMessage(), RECONNECT_DELAY_SECONDS);
                scheduleReconnect(port);
            }
        });
    }

    private void scheduleReconnect(int port) {
        if (!running.get()) return;
        group.schedule(() -> connectToFollower(port), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * "REPL SET key value\n" mesajını aktif tüm Follower kanallarına gönderir.
     * Kanal aktif değilse o an için atlar; yeniden bağlantı kurulan kanallar
     * sonraki replikasyonlara dahil edilir.
     */
    public void replicate(String key, String value) {
        String msg = "REPL SET " + key + " " + value + "\n";
        for (int port : followerPorts) {
            Channel ch = channels.get(port);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(msg).addListener((ChannelFuture f) -> {
                    if (!f.isSuccess()) {
                        log.error("[Replication] Follower :{} yazma hatası: {}", port, f.cause().getMessage());
                    }
                });
            } else {
                log.warn("[Replication] Follower :{} aktif değil — veri atlandı (key={})", port, key);
            }
        }
    }

    /**
     * Graceful shutdown: tüm aktif kanalları kapatır, thread havuzunu durdurur.
     */
    public void shutdown() throws InterruptedException {
        running.set(false);
        for (Channel ch : channels.values()) {
            ch.close().sync();
        }
        channels.clear();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        log.info("[Replication] ReplicationManager kapatıldı.");
    }
}
