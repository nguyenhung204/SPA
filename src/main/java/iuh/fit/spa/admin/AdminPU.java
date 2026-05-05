package iuh.fit.spa.admin;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.PRODUCTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import iuh.fit.spa.product.Product;
import iuh.fit.spa.product.ProductMongoRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin utilities — seed / reset data before k6 load tests.
 *
 * POST /admin/seed?count=100&stock=1000
 *   → inserts <count> products into MongoDB + loads into Hazelcast
 *
 * DELETE /admin/reset
 *   → clears products, stocks, carts maps in Hazelcast (does NOT drop Mongo)
 */
@RestController
@RequestMapping("/admin")
public class AdminPU {

    private static final Logger log = LoggerFactory.getLogger(AdminPU.class);

    private final HazelcastInstance hz;
    private final ProductMongoRepository productRepo;

    public AdminPU(HazelcastInstance hz, ProductMongoRepository productRepo) {
        this.hz = hz;
        this.productRepo = productRepo;
    }

    @RequestMapping(value = "/seed", method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> seed(
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(defaultValue = "100") int stock) {

        if (count <= 0 || count > 10_000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "count must be between 1 and 10000"));
        }
        if (stock <= 0 || stock > 1_000_000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "stock must be between 1 and 1000000"));
        }

        IMap<String, Product> productMap = hz.getMap(PRODUCTS_MAP);
        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);

        // Xóa data cũ trước để tránh trùng lặp
        log.info("[Admin] SEED — Clearing old data from MongoDB and Hazelcast...");
        productRepo.deleteAll();
        productMap.clear();
        stockMap.clear();
        hz.getMap(CARTS_MAP).clear();
        log.info("[Admin] SEED START — count={} stockPerProduct={}", count, stock);
        List<Product> products = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String id = UUID.randomUUID().toString();
            Product p = new Product(
                    id,
                    "Product-" + i,
                    "Flash-sale item #" + i,
                    BigDecimal.valueOf(10L * i),
                    stock);
            products.add(p);
        }

        // Persist to MongoDB
        log.info("[Admin] Saving {} products to MongoDB...", products.size());
        productRepo.saveAll(products);
        log.info("[Admin] MongoDB save complete.");

        // Load into Hazelcast (applies to all cluster nodes via distributed map)
        for (Product p : products) {
            productMap.put(p.getId(), p);
            stockMap.put(p.getId(), stock);
        }
        log.info("[Admin] SEED DONE — {} products loaded into Hazelcast. sampleId={}", count, products.get(0).getId());

        List<String> productIds = products.stream().map(Product::getId).toList();
        return ResponseEntity.ok(Map.of(
                "seeded", count,
                "stockPerProduct", stock,
                "sampleId", products.get(0).getId(),
                "productIds", productIds
        ));
    }

    @org.springframework.web.bind.annotation.GetMapping("/products")
    public ResponseEntity<Map<String, Object>> listProducts() {
        IMap<String, Product> productMap = hz.getMap(PRODUCTS_MAP);
        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        List<Map<String, Object>> items = productMap.values().stream()
                .map(p -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("id", p.getId());
                    entry.put("name", p.getName());
                    entry.put("stock", stockMap.getOrDefault(p.getId(), 0));
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(Map.of("count", items.size(), "products", items));
    }

    @DeleteMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        log.info("[Admin] RESET — clearing MongoDB + Hazelcast maps: products, stocks, carts");
        productRepo.deleteAll();
        hz.getMap(PRODUCTS_MAP).clear();
        hz.getMap(STOCKS_MAP).clear();
        hz.getMap("carts").clear();
        log.info("[Admin] RESET complete.");
        return ResponseEntity.ok(Map.of("status", "MongoDB + Hazelcast maps cleared"));
    }
}
