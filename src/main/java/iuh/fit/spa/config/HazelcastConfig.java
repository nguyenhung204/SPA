package iuh.fit.spa.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import iuh.fit.spa.product.Product;
import iuh.fit.spa.product.ProductMongoRepository;
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
        Config config = new Config();
        config.setClusterName("flashsale-local");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig()
                .setEnabled(true)
                .addMember("127.0.0.1");

        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        loadProductsAndStocks(hz);
        return hz;
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
