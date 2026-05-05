package iuh.fit.spa.inventory;

import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InventoryPU {

    private static final Logger log = LoggerFactory.getLogger(InventoryPU.class);

    private final HazelcastInstance hz;

    public InventoryPU(HazelcastInstance hz) {
        this.hz = hz;
    }

    public boolean deductStock(String productId, int quantity) {
        if (quantity <= 0) {
            log.warn("[Inventory] DEDUCT rejected — productId={} qty={} (invalid)", productId, quantity);
            return false;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        int attempt = 0;
        while (true) {
            attempt++;
            Integer currentStock = stockMap.get(productId);
            if (currentStock == null || currentStock < quantity) {
                log.warn("[Inventory] SOLD OUT — productId={} requested={} available={} attempt={}",
                        productId, quantity, currentStock, attempt);
                return false;
            }

            Integer newStock = currentStock - quantity;
            if (stockMap.replace(productId, currentStock, newStock)) {
                log.info("[Inventory] DEDUCTED — productId={} qty={} before={} after={} attempt={}",
                        productId, quantity, currentStock, newStock, attempt);
                return true;
            }
            log.debug("[Inventory] CAS retry — productId={} attempt={}", productId, attempt);
        }
    }

    public void restoreStock(String productId, int quantity) {
        if (quantity <= 0) {
            log.warn("[Inventory] RESTORE rejected — productId={} qty={} (invalid)", productId, quantity);
            return;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        int attempt = 0;
        while (true) {
            attempt++;
            Integer currentStock = stockMap.get(productId);
            if (currentStock == null) {
                stockMap.putIfAbsent(productId, quantity);
                log.info("[Inventory] RESTORED (new entry) — productId={} qty={}", productId, quantity);
                return;
            }

            if (stockMap.replace(productId, currentStock, currentStock + quantity)) {
                log.info("[Inventory] RESTORED — productId={} qty={} before={} after={} attempt={}",
                        productId, quantity, currentStock, currentStock + quantity, attempt);
                return;
            }
            log.debug("[Inventory] CAS retry (restore) — productId={} attempt={}", productId, attempt);
        }
    }
}
