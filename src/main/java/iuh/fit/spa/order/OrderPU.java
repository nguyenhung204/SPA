package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import iuh.fit.spa.cart.Cart;
import iuh.fit.spa.cart.CartItem;
import iuh.fit.spa.inventory.InventoryPU;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
public class OrderPU {

    private static final Logger log = LoggerFactory.getLogger(OrderPU.class);

    private final HazelcastInstance hz;
    private final InventoryPU inventoryPU;
    private final IMap<String, Cart> cartMap;
    private final com.hazelcast.collection.IQueue<OrderEvent> orderQueue;

    public OrderPU(HazelcastInstance hz, InventoryPU inventoryPU) {
        this.hz = hz;
        this.inventoryPU = inventoryPU;
        this.cartMap = hz.getMap(CARTS_MAP);
        this.orderQueue = hz.getQueue(ORDER_QUEUE);
    }

    @PostMapping
    public ResponseEntity<String> checkout(@RequestParam String userId) {
        log.info("[Checkout] START — userId={}", userId);
        Cart cart = cartMap.get(userId);
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

        // Dùng nanoTime + random để tạo ID nhanh hơn UUID.randomUUID()
        String orderId = System.nanoTime() + "-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        OrderEvent newOrder = new OrderEvent(orderId, userId, cart.getItems());

        // Chờ tối đa 2 giây nếu hàng đợi đầy
        try {
            boolean queued = orderQueue.offer(newOrder, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!queued) {
                log.error("[Checkout] QUEUE FULL — userId={}", userId);
                rollbackDeductedItems(deductedItems);
                return ResponseEntity.status(503).body("Service temporarily unavailable — please retry");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rollbackDeductedItems(deductedItems);
            return ResponseEntity.status(500).build();
        }
        
        cartMap.remove(userId);
        log.info("[Checkout] SUCCESS — userId={} orderId={}", userId, newOrder.getOrderId());
        return ResponseEntity.ok("Order Placed: " + newOrder.getOrderId());
    }


    private void rollbackDeductedItems(List<CartItem> deductedItems) {
        for (CartItem item : deductedItems) {
            inventoryPU.restoreStock(item.getProductId(), item.getQuantity());
        }
    }
}

