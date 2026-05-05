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
    private final IMap<String, Integer> stockMap;

    public InventoryPU(HazelcastInstance hz) {
        this.hz = hz;
        this.stockMap = hz.getMap(STOCKS_MAP);
    }

    /**
     * Atomically deducts stock using an EntryProcessor that runs on the
     * partition-owner thread. Eliminates CAS thundering-herd under high load:
     * a single network hop, no retries, no contention.
     */
    public boolean deductStock(String productId, int quantity) {
        if (quantity <= 0) return false;
        return Boolean.TRUE.equals(stockMap.executeOnKey(productId, new DeductStockProcessor(quantity)));
    }

    public void restoreStock(String productId, int quantity) {
        if (quantity <= 0) return;
        stockMap.executeOnKey(productId, new RestoreStockProcessor(quantity));
    }

    private static class DeductStockProcessor implements EntryProcessor<String, Integer, Boolean>, java.io.Serializable {
        private final int quantity;
        public DeductStockProcessor(int quantity) { this.quantity = quantity; }
        @Override
        public Boolean process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            if (current == null || current < quantity) return false;
            entry.setValue(current - quantity);
            return true;
        }
    }

    private static class RestoreStockProcessor implements EntryProcessor<String, Integer, Void>, java.io.Serializable {
        private final int quantity;
        public RestoreStockProcessor(int quantity) { this.quantity = quantity; }
        @Override
        public Void process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            entry.setValue((current == null ? 0 : current) + quantity);
            return null;
        }
    }
}


