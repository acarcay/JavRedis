import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Uygulama konfigürasyonunu yönetir.
 *
 * Öncelik sırası (yüksekten düşüğe):
 *   1. CLI argümanları  (args[0] = port → Follower modunda başlar)
 *   2. config.properties (classpath'te aranır)
 *   3. Gömülü varsayılan değerler
 */
public class ServerConfig {
    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    private final int          port;
    private final int          capacity;
    private final boolean      leader;
    private final List<Integer> followerPorts;

    private ServerConfig(int port, int capacity, boolean leader, List<Integer> followerPorts) {
        this.port          = port;
        this.capacity      = capacity;
        this.leader        = leader;
        this.followerPorts = Collections.unmodifiableList(followerPorts);
    }

    /**
     * Konfigürasyonu yükler. CLI argümanı varsa port olarak kullanılır ve
     * sunucu Follower modunda başlar; yoksa config.properties'teki değerler uygulanır.
     */
    public static ServerConfig load(String[] args) {
        Properties props = loadProperties();

        boolean cliPort  = args.length > 0;
        int     port     = cliPort
                           ? Integer.parseInt(args[0])
                           : Integer.parseInt(props.getProperty("server.port", "8080"));
        boolean isLeader = !cliPort
                           && "leader".equalsIgnoreCase(props.getProperty("server.role", "leader"));
        int     capacity = Integer.parseInt(props.getProperty("server.capacity", "10000"));

        List<Integer> followerPorts = Collections.emptyList();
        if (isLeader) {
            String raw = props.getProperty("replication.followers", "");
            if (!raw.isBlank()) {
                followerPorts = Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        // "localhost:8081" veya salt "8081" formatını destekle
                        .map(s -> {
                            String[] parts = s.split(":");
                            return Integer.parseInt(parts[parts.length - 1]);
                        })
                        .collect(Collectors.toList());
            }
        }

        ServerConfig cfg = new ServerConfig(port, capacity, isLeader, followerPorts);
        log.info("Konfigürasyon yüklendi: {}", cfg);
        return cfg;
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream is = ServerConfig.class.getClassLoader()
                                                .getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                log.info("config.properties classpath'ten yüklendi.");
            } else {
                log.warn("config.properties bulunamadı; varsayılan değerler kullanılıyor.");
            }
        } catch (Exception e) {
            log.error("config.properties okunurken hata oluştu: {}", e.getMessage());
        }
        return props;
    }

    // --- Getters ---
    public int          getPort()          { return port; }
    public int          getCapacity()      { return capacity; }
    public boolean      isLeader()         { return leader; }
    public List<Integer> getFollowerPorts() { return followerPorts; }

    @Override
    public String toString() {
        return String.format("ServerConfig{port=%d, capacity=%d, role=%s, followers=%s}",
                             port, capacity, leader ? "LEADER" : "FOLLOWER", followerPorts);
    }
}
