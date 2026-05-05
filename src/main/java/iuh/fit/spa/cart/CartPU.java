package iuh.fit.spa.cart;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
public class CartPU {

    private static final Logger log = LoggerFactory.getLogger(CartPU.class);
    private static final long CART_TTL_MINUTES = 30;

    private final HazelcastInstance hz;
    private final IMap<String, Cart> cartMap;

    public CartPU(HazelcastInstance hz) {
        this.hz = hz;
        this.cartMap = hz.getMap(CARTS_MAP);
    }

    @PostMapping("/add")
    public String addToCart(@RequestBody CartItemRequest req) {
        final String pid = req.getProductId();
        final int qty = req.getQuantity();

        cartMap.executeOnKey(req.getUserId(), new AddToCartProcessor(pid, qty));
        return "Added to cart";
    }

    private static class AddToCartProcessor implements com.hazelcast.map.EntryProcessor<String, Cart, Void>, java.io.Serializable {
        private final String productId;
        private final int quantity;

        public AddToCartProcessor(String productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        @Override
        public Void process(java.util.Map.Entry<String, Cart> entry) {
            Cart cart = entry.getValue();
            if (cart == null) {
                cart = new Cart();
            }
            cart.addItem(productId, quantity);
            entry.setValue(cart);
            return null;
        }
    }

    @GetMapping("/{userId}")

    public Cart getCart(@PathVariable String userId) {
        return cartMap.get(userId);
    }
}

