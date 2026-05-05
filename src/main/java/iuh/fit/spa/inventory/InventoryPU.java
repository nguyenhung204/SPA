package iuh.fit.spa.inventory;

import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.stereotype.Service;

@Service
public class InventoryPU {

    private final HazelcastInstance hz;

    public InventoryPU(HazelcastInstance hz) {
        this.hz = hz;
    }

    public boolean deductStock(String productId, int quantity) {
        if (quantity <= 0) {
            return false;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        while (true) {
            Integer currentStock = stockMap.get(productId);
            if (currentStock == null || currentStock < quantity) {
                return false;
            }

            Integer newStock = currentStock - quantity;
            if (stockMap.replace(productId, currentStock, newStock)) {
                return true;
            }
        }
    }

    public void restoreStock(String productId, int quantity) {
        if (quantity <= 0) {
            return;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        while (true) {
            Integer currentStock = stockMap.get(productId);
            if (currentStock == null) {
                stockMap.putIfAbsent(productId, quantity);
                return;
            }

            if (stockMap.replace(productId, currentStock, currentStock + quantity)) {
                return;
            }
        }
    }
}
