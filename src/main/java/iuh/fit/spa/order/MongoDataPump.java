package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.collection.IQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class MongoDataPump {

    private final HazelcastInstance hz;
    private final OrderMongoRepository orderRepo;
    private Thread workerThread;

    public MongoDataPump(HazelcastInstance hz, OrderMongoRepository orderRepo) {
        this.hz = hz;
        this.orderRepo = orderRepo;
    }

    @PostConstruct
    public void init() {
        workerThread = new Thread(this::pumpOrders, "mongo-data-pump");
        workerThread.start();
    }

    @PreDestroy
    public void shutdown() {
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void pumpOrders() {
        IQueue<OrderEvent> queue = hz.getQueue(ORDER_QUEUE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                OrderEvent event = queue.take();
                orderRepo.save(new OrderDocument(event.getOrderId(), event.getUserId(), event.getItems()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
