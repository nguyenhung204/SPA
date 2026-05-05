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
    private static final int THREAD_COUNT = 50;


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
        java.util.List<OrderEvent> buffer = new java.util.ArrayList<>();
        try {
            IQueue<OrderEvent> queue = hz.getQueue(ORDER_QUEUE);
            while (!Thread.currentThread().isInterrupted()) {
                if (!hz.getLifecycleService().isRunning()) break;
                
                try {
                    // Lấy tối đa 100 đơn hàng từ queue trong 1 lần
                    queue.drainTo(buffer, 100);
                    
                    if (buffer.isEmpty()) {
                        // Nếu queue trống, đợi tối đa 1 giây để lấy 1 đơn hàng đầu tiên
                        OrderEvent first = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                        if (first != null) {
                            buffer.add(first);
                            // Sau khi có 1 cái, cố lấy thêm các cái đang chờ khác
                            queue.drainTo(buffer, 99);
                        }
                    }

                    if (!buffer.isEmpty()) {
                        persistBulkWithRetry(buffer);
                        buffer.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[MongoDataPump] Unexpected error: {}", e.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } catch (Exception e) {
            log.error("[MongoDataPump] Worker thread crashed: {}", e.getMessage());
        }
    }

    private void persistBulkWithRetry(java.util.List<OrderEvent> events) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                java.util.List<OrderDocument> docs = events.stream()
                    .map(e -> new OrderDocument(e.getOrderId(), e.getUserId(), e.getItems()))
                    .collect(java.util.stream.Collectors.toList());
                
                mongoTemplate.insertAll(docs);
                log.info("[MongoDataPump] Bulk persisted {} orders — attempt={}", events.size(), attempt);
                return;
            } catch (Exception e) {
                log.error("[MongoDataPump] Bulk insert failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    log.error("[MongoDataPump] CRITICAL: Lost batch of {} orders!", events.size());
                }
            }
        }
    }
}
