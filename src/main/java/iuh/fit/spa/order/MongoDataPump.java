package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.ORDER_EVENTS_TOPIC;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.MessageListener;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class MongoDataPump implements MessageListener<OrderEvent> {

    private final HazelcastInstance hz;
    private final OrderMongoRepository orderRepo;

    public MongoDataPump(HazelcastInstance hz, OrderMongoRepository orderRepo) {
        this.hz = hz;
        this.orderRepo = orderRepo;
    }

    @PostConstruct
    public void init() {
        hz.<OrderEvent>getTopic(ORDER_EVENTS_TOPIC).addMessageListener(this);
    }

    @Override
    public void onMessage(Message<OrderEvent> message) {
        OrderEvent event = message.getMessageObject();
        orderRepo.save(new OrderDocument(event.getOrderId(), event.getUserId(), event.getItems()));
    }
}
