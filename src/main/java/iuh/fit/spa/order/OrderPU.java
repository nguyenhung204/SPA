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

        // Atomic get-and-remove: 1 network hop thay vì 2 (get rồi remove riêng lẻ)
        // Dùng static inner class để Hazelcast có thể serialize gửi sang node khác trong cluster
        Cart cart = cartMap.executeOnKey(userId, new GetAndRemoveCartProcessor());

        if (cart == null || cart.isEmpty()) {
            log.warn("[Checkout] EMPTY CART — userId={}", userId);
            return ResponseEntity.badRequest().body("Cart is empty");
        }

        List<CartItem> deductedItems = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            if (!inventoryPU.deductStock(item.getProductId(), item.getQuantity())) {
                // Rollback stock + đặt lại cart nếu sold out
                rollbackDeductedItems(deductedItems);
                cartMap.putIfAbsent(userId, cart); // khôi phục giỏ hàng
                return ResponseEntity.badRequest().body("Sold Out!");
            }
            deductedItems.add(item);
        }

        String orderId = System.nanoTime() + "-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        OrderEvent newOrder = new OrderEvent(orderId, userId, cart.getItems());

        try {
            // Tăng timeout lên 2s — dưới tải cao Hazelcast cần thêm thời gian enqueue
            boolean queued = orderQueue.offer(newOrder, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!queued) {
                log.warn("[Checkout] QUEUE FULL — userId={}", userId);
                rollbackDeductedItems(deductedItems);
                cartMap.putIfAbsent(userId, cart);
                // Trả về 503 để k6 biết server đang quá tải (backpressure)
                return ResponseEntity.status(503).body("System busy, please retry");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rollbackDeductedItems(deductedItems);
            cartMap.putIfAbsent(userId, cart);
            return ResponseEntity.status(500).build();
        } catch (com.hazelcast.core.HazelcastInstanceNotActiveException e) {
            log.error("[Checkout] Hazelcast not active — userId={}", userId);
            return ResponseEntity.status(503).body("Cluster not available");
        }

        log.info("[Checkout] SUCCESS — userId={} orderId={}", userId, newOrder.getOrderId());
        return ResponseEntity.ok("Order Placed: " + newOrder.getOrderId());
    }



    private void rollbackDeductedItems(List<CartItem> deductedItems) {
        for (CartItem item : deductedItems) {
            inventoryPU.restoreStock(item.getProductId(), item.getQuantity());
        }
    }

    /**
     * Atomically reads the cart and removes it in a single network hop.
     * Must be a static Serializable class so Hazelcast can send it to the
     * partition-owner node in a multi-node cluster.
     */
    private static class GetAndRemoveCartProcessor
            implements com.hazelcast.map.EntryProcessor<String, Cart, Cart>, java.io.Serializable {
        @Override
        public Cart process(java.util.Map.Entry<String, Cart> entry) {
            Cart c = entry.getValue();
            if (c != null && !c.isEmpty()) {
                entry.setValue(null); // xoá ngay trong cùng 1 operation
            }
            return c;
        }
    }
}


