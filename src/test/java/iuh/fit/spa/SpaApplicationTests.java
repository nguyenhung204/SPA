package iuh.fit.spa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import iuh.fit.spa.product.ProductMongoRepository;

@SpringBootTest
class SpaApplicationTests {

    @MockitoBean
    ProductMongoRepository productRepo;

    @Test
    void contextLoads() {
    }

}
