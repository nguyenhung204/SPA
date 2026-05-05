package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import iuh.fit.spa.cart.CartItem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MongoDataPump {

    private static final Logger log = LoggerFactory.getLogger(MongoDataPump.class);
    private static final int MAX_RETRIES = 3;

    private final HazelcastInstance hz;
    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;
    private Thread workerThread;

    public MongoDataPump(HazelcastInstance hz,
                         MongoTemplate mongoTemplate,
                         TransactionTemplate transactionTemplate) {
        this.hz = hz;
        this.mongoTemplate = mongoTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        log.info("[MongoDataPump] Starting background worker thread...");
        workerThread = new Thread(this::pumpOrders, "mongo-data-pump");
        workerThread.start();
        log.info("[MongoDataPump] Worker thread started.");
    }

    @PreDestroy
    public void shutdown() {
        log.info("[MongoDataPump] Shutting down worker thread...");
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void pumpOrders() {
        log.info("[MongoDataPump] Listening on order-queue...");
        IQueue<OrderEvent> queue = hz.getQueue(ORDER_QUEUE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OrderEvent event = queue.take();
                log.info("[MongoDataPump] Order received — orderId={} userId={} items={}",
                        event.getOrderId(), event.getUserId(), event.getItems().size());
                persistWithRetry(event);
            } catch (InterruptedException e) {
                log.warn("[MongoDataPump] Worker interrupted — stopping.");
                Thread.currentThread().interrupt();
            }
        }
        log.info("[MongoDataPump] Worker thread stopped.");
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

    /**
     * Atomic transaction:
     *   1. Insert order document into `orders` collection
     *   2. $inc stock for each item in `products` collection
     * If either step fails, the whole transaction is rolled back.
     */
    private void persistWithTransaction(OrderEvent event) {
        transactionTemplate.execute(status -> {
            // Step 1: save order
            OrderDocument doc = new OrderDocument(event.getOrderId(), event.getUserId(), event.getItems());
            mongoTemplate.save(doc);
            log.debug("[MongoDataPump] Order document saved — orderId={}", event.getOrderId());

            // Step 2: decrement stock for each item atomically
            for (CartItem item : event.getItems()) {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(item.getProductId())),
                        new Update().inc("stock", -item.getQuantity()),
                        "products"
                );
                log.debug("[MongoDataPump] Stock decremented — productId={} qty={}",
                        item.getProductId(), item.getQuantity());
            }
            return null;
        });
    }
}
