package iuh.fit.spa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import iuh.fit.spa.product.Product;
import iuh.fit.spa.product.ProductMongoRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class HazelcastConfigTest {

    @Test
    void loadsProductsAndStocksFromMongoOnStartup() {
        ProductMongoRepository productRepo = mock(ProductMongoRepository.class);
        Product product = new Product("p1", "Keyboard", "Mechanical", BigDecimal.valueOf(99), 7);
        when(productRepo.findAll()).thenReturn(List.of(product));

        HazelcastInstance hz = new HazelcastConfig(productRepo).hazelcastInstance();
        try {
            assertThat(hz.<String, Product>getMap(HazelcastConfig.PRODUCTS_MAP).get("p1").getName())
                    .isEqualTo("Keyboard");
            assertThat(hz.<String, Integer>getMap(HazelcastConfig.STOCKS_MAP).get("p1"))
                    .isEqualTo(7);
        } finally {
            hz.shutdown();
        }
    }
}
