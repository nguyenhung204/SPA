package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.ORDER_QUEUE;

import com.hazelcast.core.HazelcastInstance;
import iuh.fit.spa.cart.Cart;
import iuh.fit.spa.cart.CartItem;
import iuh.fit.spa.inventory.InventoryPU;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    public OrderPU(HazelcastInstance hz, InventoryPU inventoryPU) {
        this.hz = hz;
        this.inventoryPU = inventoryPU;
    }

    @PostMapping
    public ResponseEntity<String> checkout(@RequestParam String userId) {
        log.info("[Checkout] START — userId={}", userId);
        Cart cart = hz.<String, Cart>getMap(CARTS_MAP).get(userId);
        if (cart == null || cart.isEmpty()) {
            log.warn("[Checkout] REJECTED — userId={} cart is empty or null", userId);
            return ResponseEntity.badRequest().body("Cart is empty");
        }

        log.info("[Checkout] Cart loaded — userId={} items={}", userId, cart.getItems().size());
        List<CartItem> deductedItems = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            log.info("[Checkout] Deducting stock — productId={} qty={}", item.getProductId(), item.getQuantity());
            if (!inventoryPU.deductStock(item.getProductId(), item.getQuantity())) {
                log.warn("[Checkout] SOLD OUT — productId={} qty={} — rolling back {} item(s)",
                        item.getProductId(), item.getQuantity(), deductedItems.size());
                rollbackDeductedItems(deductedItems);
                return ResponseEntity.badRequest().body("Sold Out!");
            }
            deductedItems.add(item);
        }



        OrderEvent newOrder = new OrderEvent(UUID.randomUUID().toString(), userId, cart.getItems());
        boolean queued = hz.<OrderEvent>getQueue(ORDER_QUEUE).offer(newOrder);
        if (!queued) {
            log.error("[Checkout] ORDER QUEUE FULL — rolling back stock for orderId={} userId={}", newOrder.getOrderId(), userId);
            rollbackDeductedItems(deductedItems);
            return ResponseEntity.status(503).body("Service temporarily unavailable — please retry");
        }
        log.info("[Checkout] Order queued — orderId={} userId={} items={}", newOrder.getOrderId(), userId, cart.getItems().size());
        hz.getMap(CARTS_MAP).remove(userId);

        return ResponseEntity.ok("Order Placed: " + newOrder.getOrderId());
    }

    private void rollbackDeductedItems(List<CartItem> deductedItems) {
        log.info("[Checkout] ROLLBACK — restoring {} item(s)", deductedItems.size());
        for (CartItem item : deductedItems) {
            log.info("[Checkout] Restoring stock — productId={} qty={}", item.getProductId(), item.getQuantity());
            inventoryPU.restoreStock(item.getProductId(), item.getQuantity());
        }
        log.info("[Checkout] ROLLBACK complete");
    }
}
