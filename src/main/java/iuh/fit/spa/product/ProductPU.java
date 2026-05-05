package iuh.fit.spa.product;

import static iuh.fit.spa.config.HazelcastConfig.PRODUCTS_MAP;

import com.hazelcast.core.HazelcastInstance;
import java.util.Collection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductPU {

    private final HazelcastInstance hz;

    public ProductPU(HazelcastInstance hz) {
        this.hz = hz;
    }

    @GetMapping
    public Collection<Product> getAll() {
        return hz.<String, Product>getMap(PRODUCTS_MAP).values();
    }

    @GetMapping("/{id}")
    public Product getById(@PathVariable String id) {
        return hz.<String, Product>getMap(PRODUCTS_MAP).get(id);
    }
}
