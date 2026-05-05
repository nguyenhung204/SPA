package iuh.fit.spa.inventory;

import static iuh.fit.spa.config.HazelcastConfig.STOCKS_MAP;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryPUTest {

    private HazelcastInstance hz;
    private InventoryPU inventoryPU;

    @BeforeEach
    void setUp() {
        Config config = singleNodeConfig();
        hz = Hazelcast.newHazelcastInstance(config);
        inventoryPU = new InventoryPU(hz);
    }

    @AfterEach
    void tearDown() {
        hz.shutdown();
    }

    @Test
    void deductStockUsesCompareAndSwapUnderConcurrency() throws Exception {
        hz.<String, Integer>getMap(STOCKS_MAP).put("p1", 10);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                start.await();
                return inventoryPU.deductStock("p1", 1);
            });
        }

        List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        long successfulDeductions = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successfulDeductions++;
            }
        }
        executor.shutdownNow();

        assertThat(successfulDeductions).isEqualTo(10);
        assertThat(hz.<String, Integer>getMap(STOCKS_MAP).get("p1")).isZero();
    }

    private Config singleNodeConfig() {
        Config config = new Config();
        config.setClusterName("test-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return config;
    }
}
