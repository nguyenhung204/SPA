package iuh.fit.spa.cart;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
public class CartPU {

    private static final long CART_TTL_MINUTES = 30;

    private final HazelcastInstance hz;

    public CartPU(HazelcastInstance hz) {
        this.hz = hz;
    }

    @PostMapping("/add")
    public String addToCart(@RequestBody CartItemRequest req) {
        IMap<String, Cart> cartMap = hz.getMap(CARTS_MAP);
        Cart cart = cartMap.getOrDefault(req.getUserId(), new Cart());
        cart.addItem(req.getProductId(), req.getQuantity());
        cartMap.put(req.getUserId(), cart, CART_TTL_MINUTES, TimeUnit.MINUTES);
        return "Added to cart";
    }

    @GetMapping("/{userId}")
    public Cart getCart(@PathVariable String userId) {
        return hz.<String, Cart>getMap(CARTS_MAP).get(userId);
    }
}
