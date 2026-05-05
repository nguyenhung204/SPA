package iuh.fit.spa.inventory;

import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StockSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockSyncScheduler.class);

    private final HazelcastInstance hz;
    private final MongoTemplate mongoTemplate;

    public StockSyncScheduler(HazelcastInstance hz, MongoTemplate mongoTemplate) {
        this.hz = hz;
        this.mongoTemplate = mongoTemplate;
    }

    @Scheduled(fixedDelayString = "${spa.stock-sync.interval-ms:10000}")
    public void syncStocksToMongo() {
        IMap<String, Integer> stocksMap = hz.getMap(STOCKS_MAP);
        if (stocksMap.isEmpty()) {
            return;
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(stocksMap.entrySet());

        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, "products");
        for (Map.Entry<String, Integer> entry : entries) {
            bulk.updateOne(
                    Query.query(Criteria.where("_id").is(entry.getKey())),
                    Update.update("stock", entry.getValue())
            );
        }
        bulk.execute();

        log.debug("[StockSync] Synced {} product stocks to MongoDB.", entries.size());
    }
}
