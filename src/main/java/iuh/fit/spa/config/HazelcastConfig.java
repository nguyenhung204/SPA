package iuh.fit.spa.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import iuh.fit.spa.product.Product;
import iuh.fit.spa.product.ProductMongoRepository;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfig {

    private static final Logger log = LoggerFactory.getLogger(HazelcastConfig.class);

    public static final String PRODUCTS_MAP = "products";
    public static final String CARTS_MAP = "carts";
    public static final String STOCKS_MAP = "stocks";
    public static final String ORDER_QUEUE = "order-queue";

    private final ProductMongoRepository productRepo;

    public HazelcastConfig(ProductMongoRepository productRepo) {
        this.productRepo = productRepo;
    }

    @Bean(destroyMethod = "shutdown")
    public HazelcastInstance hazelcastInstance() {
        String localIp = detectLanIp();
        log.info("[Hazelcast] Detected LAN IP: {}", localIp);

        Config config = new Config();
        config.setClusterName("flashsale-local");

        config.getNetworkConfig().setPublicAddress(localIp + ":5701");
        config.getNetworkConfig().getInterfaces()
                .setEnabled(true)
                .addInterface(localIp);

        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig()
                .setEnabled(true)
                .addMember("127.0.0.1")
                .addMember("192.168.11.176")
                .addMember("192.168.10.227")
                .addMember("192.168.11.188")
                .addMember("192.168.11.195");

        // Carts Map Config: Dữ liệu tạm, tắt Backup để tăng tốc độ ghi tối đa
        config.addMapConfig(new com.hazelcast.config.MapConfig(CARTS_MAP)
                .setTimeToLiveSeconds(30 * 60)
                .setBackupCount(0)); // 0 backup = ghi thẳng vào RAM, không chờ đợi qua mạng

        // Stocks Map: Cần độ chính xác cao nhưng cũng cần tốc độ
        config.addMapConfig(new com.hazelcast.config.MapConfig(STOCKS_MAP)
                .setBackupCount(0)); 

        // Tăng số lượng thread xử lý I/O của Hazelcast
        config.getExecutorConfig("default")
                .setPoolSize(100)
                .setQueueCapacity(10000);

        log.info("[Hazelcast] Starting cluster 'flashsale-local' with NO-BACKUP optimization for speed.");

        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        log.info("[Hazelcast] Instance started. Cluster size: {}", hz.getCluster().getMembers().size());
        loadProductsAndStocks(hz);
        return hz;
    }

    private String detectLanIp() {
        // Allow override via -Dhazelcast.local.ip=x.x.x.x
        String override = System.getProperty("hazelcast.local.ip");
        if (override != null && !override.isBlank()) return override;

        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                // Skip hotspot, VMware, VirtualBox, Hyper-V adapters
                String name = nic.getDisplayName().toLowerCase();
                if (name.contains("virtual") || name.contains("vmware")
                        || name.contains("vbox") || name.contains("hyper-v")
                        || name.contains("pseudo") || name.contains("bluetooth")) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    // Skip hotspot ranges (192.168.43.x, 192.168.137.x)
                    if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.")) continue;
                    return ip;
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void loadProductsAndStocks(HazelcastInstance hz) {
        IMap<String, Product> productMap = hz.getMap(PRODUCTS_MAP);
        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);

        log.info("[RAM Load] Fetching products from MongoDB...");
        List<Product> products = productRepo.findAll();
        if (products.isEmpty()) {
            log.warn("[RAM Load] No products found in MongoDB — Hazelcast maps are empty.");
            return;
        }

        log.info("[RAM Load] Found {} products in MongoDB. Loading into Hazelcast...", products.size());
        for (Product product : products) {
            productMap.put(product.getId(), product);
            stockMap.put(product.getId(), product.getStock());
            log.debug("[RAM Load] Loaded product id={} name='{}' stock={}", product.getId(), product.getName(), product.getStock());
        }
        log.info("[RAM Load] Done — {} products and stocks loaded into Hazelcast RAM.", products.size());
    }
}
