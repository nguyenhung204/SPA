package iuh.fit.spa.inventory;

import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import java.util.Map;
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

    /**
     * Atomically deducts stock using an EntryProcessor that runs on the
     * partition-owner thread. Eliminates CAS thundering-herd under high load:
     * a single network hop, no retries, no contention.
     */
    public boolean deductStock(String productId, int quantity) {
        if (quantity <= 0) {
            log.warn("[Inventory] DEDUCT rejected — productId={} qty={} (invalid)", productId, quantity);
            return false;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        Boolean result = (Boolean) stockMap.executeOnKey(productId,
                (EntryProcessor<String, Integer, Boolean>) entry -> {
                    Integer current = entry.getValue();
                    if (current == null || current < quantity) return false;
                    entry.setValue(current - quantity);
                    return true;
                });

        if (Boolean.TRUE.equals(result)) {
            log.info("[Inventory] DEDUCTED — productId={} qty={}", productId, quantity);
        } else {
            log.warn("[Inventory] SOLD OUT — productId={} requested={}", productId, quantity);
        }
        return Boolean.TRUE.equals(result);
    }

    /**
     * Atomically restores stock using an EntryProcessor (used on rollback).
     */
    public void restoreStock(String productId, int quantity) {
        if (quantity <= 0) {
            log.warn("[Inventory] RESTORE rejected — productId={} qty={} (invalid)", productId, quantity);
            return;
        }

        IMap<String, Integer> stockMap = hz.getMap(STOCKS_MAP);
        stockMap.executeOnKey(productId,
                (EntryProcessor<String, Integer, Void>) entry -> {
                    Integer current = entry.getValue();
                    entry.setValue((current == null ? 0 : current) + quantity);
                    return null;
                });
        log.info("[Inventory] RESTORED — productId={} qty={}", productId, quantity);
    }
}
