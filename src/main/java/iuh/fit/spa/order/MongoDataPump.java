package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.collection.IQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MongoDataPump {

    private static final Logger log = LoggerFactory.getLogger(MongoDataPump.class);

    private final HazelcastInstance hz;
    private final OrderMongoRepository orderRepo;
    private Thread workerThread;

    public MongoDataPump(HazelcastInstance hz, OrderMongoRepository orderRepo) {
        this.hz = hz;
        this.orderRepo = orderRepo;
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
                OrderDocument doc = new OrderDocument(event.getOrderId(), event.getUserId(), event.getItems());
                orderRepo.save(doc);
                log.info("[MongoDataPump] Order persisted to MongoDB — orderId={}", event.getOrderId());
            } catch (InterruptedException e) {
                log.warn("[MongoDataPump] Worker interrupted — stopping.");
                Thread.currentThread().interrupt();
            }
        }
        log.info("[MongoDataPump] Worker thread stopped.");
    }
}
