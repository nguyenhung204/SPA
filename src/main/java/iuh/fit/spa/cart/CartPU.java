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

    public CartPU(HazelcastInstance hz) {
        this.hz = hz;
    }

    @PostMapping("/add")
    public String addToCart(@RequestBody CartItemRequest req) {
        log.info("[Cart] ADD — userId={} productId={} qty={}", req.getUserId(), req.getProductId(), req.getQuantity());
        IMap<String, Cart> cartMap = hz.getMap(CARTS_MAP);
        Cart cart = cartMap.getOrDefault(req.getUserId(), new Cart());
        cart.addItem(req.getProductId(), req.getQuantity());
        cartMap.put(req.getUserId(), cart, CART_TTL_MINUTES, TimeUnit.MINUTES);
        log.info("[Cart] UPDATED — userId={} totalItems={} ttl={}min", req.getUserId(), cart.getItems().size(), CART_TTL_MINUTES);
        return "Added to cart";
    }

    @GetMapping("/{userId}")
    public Cart getCart(@PathVariable String userId) {
        Cart cart = hz.<String, Cart>getMap(CARTS_MAP).get(userId);
        log.info("[Cart] GET — userId={} found={}", userId, cart != null);
        return cart;
    }
}
