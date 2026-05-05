package iuh.fit.spa.order;

import static iuh.fit.spa.config.HazelcastConfig.CARTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.PRODUCTS_MAP;
import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import iuh.fit.spa.cart.Cart;
import iuh.fit.spa.cart.CartItemRequest;
import iuh.fit.spa.cart.CartPU;
import iuh.fit.spa.inventory.InventoryPU;
import iuh.fit.spa.product.Product;
import iuh.fit.spa.product.ProductPU;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SpaceBasedFlowTest {

    private HazelcastInstance hz;
    private ProductPU productPU;
    private CartPU cartPU;
    private OrderPU orderPU;
    private OrderMongoRepository orderRepo;
    private MongoDataPump dataPump;

    @BeforeEach
    void setUp() {
        hz = Hazelcast.newHazelcastInstance(singleNodeConfig());
        productPU = new ProductPU(hz);
        cartPU = new CartPU(hz);
        orderPU = new OrderPU(hz, new InventoryPU(hz));
        orderRepo = mock(OrderMongoRepository.class);
        dataPump = new MongoDataPump(hz, orderRepo);
        dataPump.init();

        Product product = new Product("p1", "Flash Sale Phone", "RAM-backed product", BigDecimal.valueOf(499), 5);
        hz.<String, Product>getMap(PRODUCTS_MAP).put(product.getId(), product);
        hz.<String, Integer>getMap(STOCKS_MAP).put(product.getId(), product.getStock());
    }

    @AfterEach
    void tearDown() {
        if (dataPump != null) {
            dataPump.shutdown();
        }
        hz.shutdown();
    }

    @Test
    void productAndCartReadWriteOnlyHazelcastRam() {
        assertThat(productPU.getById("p1").getName()).isEqualTo("Flash Sale Phone");

        cartPU.addToCart(cartRequest("u1", "p1", 2));
        cartPU.addToCart(cartRequest("u1", "p1", 1));

        Cart cart = cartPU.getCart("u1");
        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    void checkoutDeductsStockClearsCartAndPublishesOrderToDataPump() {
        cartPU.addToCart(cartRequest("u1", "p1", 2));

        ResponseEntity<String> response = orderPU.checkout("u1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hz.<String, Integer>getMap(STOCKS_MAP).get("p1")).isEqualTo(3);
        assertThat(hz.<String, Cart>getMap(CARTS_MAP).get("u1")).isNull();

        ArgumentCaptor<OrderDocument> orderCaptor = ArgumentCaptor.forClass(OrderDocument.class);
        verify(orderRepo, timeout(3000)).save(orderCaptor.capture());
        OrderDocument savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.getUserId()).isEqualTo("u1");
        assertThat(savedOrder.getItems()).hasSize(1);
        assertThat(savedOrder.getItems().get(0).getProductId()).isEqualTo("p1");
        assertThat(savedOrder.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void checkoutSoldOutKeepsCartAndDoesNotPublishOrder() {
        cartPU.addToCart(cartRequest("u1", "p1", 6));

        ResponseEntity<String> response = orderPU.checkout("u1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Sold Out!");
        assertThat(hz.<String, Integer>getMap(STOCKS_MAP).get("p1")).isEqualTo(5);
        assertThat(hz.<String, Cart>getMap(CARTS_MAP).get("u1")).isNotNull();
        verify(orderRepo, after(300).never()).save(any());
    }

    @Test
    void queueDistributesOneOrderToOnlyOneDataPump() {
        MongoDataPump secondDataPump = new MongoDataPump(hz, orderRepo);
        secondDataPump.init();
        try {
            cartPU.addToCart(cartRequest("u1", "p1", 1));

            ResponseEntity<String> response = orderPU.checkout("u1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(orderRepo, timeout(3000).times(1)).save(any());
        } finally {
            secondDataPump.shutdown();
        }
    }

    private CartItemRequest cartRequest(String userId, String productId, int quantity) {
        CartItemRequest request = new CartItemRequest();
        request.setUserId(userId);
        request.setProductId(productId);
        request.setQuantity(quantity);
        return request;
    }

    private Config singleNodeConfig() {
        Config config = new Config();
        config.setClusterName("test-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return config;
    }
}
