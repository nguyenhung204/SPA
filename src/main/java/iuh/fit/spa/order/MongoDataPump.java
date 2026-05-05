package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class MongoDataPump {

    private static final Logger log = LoggerFactory.getLogger(MongoDataPump.class);
    private static final int MAX_RETRIES = 3;

    private final HazelcastInstance hz;
    private final MongoTemplate mongoTemplate;
    private java.util.concurrent.ExecutorService executor;
    private static final int THREAD_COUNT = 20;

    public MongoDataPump(HazelcastInstance hz, MongoTemplate mongoTemplate) {
        this.hz = hz;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void init() {
        log.info("[MongoDataPump] Starting background worker pool with {} threads...", THREAD_COUNT);
        executor = java.util.concurrent.Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(this::pumpOrders);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[MongoDataPump] Shutting down worker pool...");
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pumpOrders() {
        IQueue<OrderEvent> queue = hz.getQueue(ORDER_QUEUE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OrderEvent event = queue.take();
                persistWithRetry(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[MongoDataPump] Unexpected error in worker thread: {}", e.getMessage());
            }
        }
    }

    private void persistWithRetry(OrderEvent event) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                persistWithTransaction(event);
                log.info("[MongoDataPump] Order persisted — orderId={} attempt={}", event.getOrderId(), attempt);
                return;
            } catch (Exception e) {
                log.error("[MongoDataPump] Transaction failed — orderId={} attempt={}/{} error={}",
                        event.getOrderId(), attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    log.error("[MongoDataPump] DEAD LETTER — orderId={} giving up, manual intervention needed.",
                            event.getOrderId());
                }
            }
        }
    }

    private void persistWithTransaction(OrderEvent event) {
        OrderDocument doc = new OrderDocument(event.getOrderId(), event.getUserId(), event.getItems());
        mongoTemplate.save(doc);
        log.debug("[MongoDataPump] Order document saved — orderId={}", event.getOrderId());
    }
}
