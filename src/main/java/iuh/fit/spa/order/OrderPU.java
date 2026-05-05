package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.ORDER_EVENTS_TOPIC;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.topic.ITopic;
import iuh.fit.spa.cart.Cart;
import iuh.fit.spa.cart.CartItem;
import iuh.fit.spa.inventory.InventoryPU;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
public class OrderPU {

    private final HazelcastInstance hz;
    private final InventoryPU inventoryPU;

    public OrderPU(HazelcastInstance hz, InventoryPU inventoryPU) {
        this.hz = hz;
        this.inventoryPU = inventoryPU;
    }

    @PostMapping
    public ResponseEntity<String> checkout(@RequestParam String userId) {
        Cart cart = hz.<String, Cart>getMap(CARTS_MAP).get(userId);
        if (cart == null || cart.isEmpty()) {
            return ResponseEntity.badRequest().body("Cart is empty");
        }

        List<CartItem> deductedItems = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            if (!inventoryPU.deductStock(item.getProductId(), item.getQuantity())) {
                rollbackDeductedItems(deductedItems);
                return ResponseEntity.badRequest().body("Sold Out!");
            }
            deductedItems.add(item);
        }

        hz.getMap(CARTS_MAP).remove(userId);
        OrderEvent newOrder = new OrderEvent(UUID.randomUUID().toString(), userId, cart.getItems());
        ITopic<OrderEvent> topic = hz.getTopic(ORDER_EVENTS_TOPIC);
        topic.publish(newOrder);

        return ResponseEntity.ok("Order Placed: " + newOrder.getOrderId());
    }

    private void rollbackDeductedItems(List<CartItem> deductedItems) {
        for (CartItem item : deductedItems) {
            inventoryPU.restoreStock(item.getProductId(), item.getQuantity());
        }
    }
}
