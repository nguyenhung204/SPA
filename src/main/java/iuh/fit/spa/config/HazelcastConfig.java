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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfig {

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

        Config config = new Config();
        config.setClusterName("flashsale-local");

        config.getNetworkConfig().setPublicAddress(localIp + ":5701");
        config.getNetworkConfig().getInterfaces()
                .setEnabled(true)
                .addInterface(localIp);

        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig()
                .setEnabled(true)
                .addMember("192.168.11.176")
                .addMember("192.168.10.227")
                .addMember("192.168.11.188");

        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
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

        List<Product> products = productRepo.findAll();
        if (products == null) {
            return;
        }

        for (Product product : products) {
            productMap.put(product.getId(), product);
            stockMap.put(product.getId(), product.getStock());
        }
    }
}
