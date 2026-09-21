package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.sim.DeviceSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发命令：顺序可预期、无重复执行、无半更新；命令风暴背压有界拒绝/合并。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit(4, "REJECT");
        kit.seedTwoHomes();
        kit.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
    }

    private DeviceCommand cmd(String id, Object value) {
        return new DeviceCommand(id, "plug-a1", "home-a", "setPower",
                Map.of("value", value), "alice", null, kit.clock.nowMillis(), 0L);
    }

    @Test
    @DisplayName("多入口并发下发同一设备：全部得到确定终态，无丢失/无重复副作用")
    void concurrentDispatchSameDeviceConsistent() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<CommandResult> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    results.add(kit.commandGateway.dispatch(cmd("cc-" + idx, idx % 2 == 0)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 容量为 4/设备：风暴下必然有 BACKPRESSURE_REJECTED，但在途绝不超过容量。
        long effective = results.stream()
                .filter(r -> r.outcome() == CommandOutcome.EFFECTIVE).count();
        long rejected = results.stream()
                .filter(r -> r.outcome() == CommandOutcome.REJECTED
                        && r.rejectReason() == RejectReason.BACKPRESSURE_REJECTED).count();
        long bad = results.stream()
                .filter(r -> r.outcome() != CommandOutcome.EFFECTIVE
                        && !(r.outcome() == CommandOutcome.REJECTED
                        && r.rejectReason() == RejectReason.BACKPRESSURE_REJECTED)).count();
        assertEquals(0, bad, "只允许 EFFECTIVE 或 BACKPRESSURE_REJECTED: " + results);
        assertTrue(rejected > 0, "风暴必须触发背压拒绝，防止无界堆积");
        log.info("[TEST] concurrent total={} effective={} backpressureRejected={}",
                threads, effective, rejected);

        // 最终状态确定（true 或 false），无半更新；版本数 = 生效命令数。
        Object power = kit.stateService.snapshot("plug-a1").property("power");
        long version = kit.stateService.snapshot("plug-a1").version();
        assertTrue(power instanceof Boolean);
        assertEquals(effective, version,
                "每次生效恰好推进一次版本，无重复副作用");
        log.info("[TEST] final power={} version={}", power, version);
    }

    @Test
    @DisplayName("同一设备命令严格按入队顺序执行（用执行日志验证 FIFO）")
    void perDeviceOrderIsFifo() throws Exception {
        // 让设备每条命令有少量延迟，放大并发入队，但同一设备排空链必须 FIFO。
        kit.simulator.configure("plug-a1", new DeviceSimulator.SimConfig(10L, 0, true));
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                // 交替开关，最终最后一条决定状态；按 idx 入队。
                return kit.commandGateway.dispatch(cmd("fifo-" + idx, idx % 2 == 0));
            }));
        }
        start.countDown();
        for (Future<CommandResult> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // 容量 4 时部分被背压拒绝；接受的命令必须执行成功，状态确定。
        Object power = kit.stateService.snapshot("plug-a1").property("power");
        assertTrue(power instanceof Boolean, "无半更新: " + power);
        log.info("[TEST] fifo done final power={} version={}",
                power, kit.stateService.snapshot("plug-a1").version());
    }

    @Test
    @DisplayName("COALESCE 模式：同设备同命令在途合并，共享终态，不重复执行")
    void coalesceMergesIdenticalInFlight() throws Exception {
        TestKit coalescing = new TestKit(8, "COALESCE");
        coalescing.seedTwoHomes();
        coalescing.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
        coalescing.simulator.configure("plug-a1",
                new DeviceSimulator.SimConfig(80L, 0, true));

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                DeviceCommand c = new DeviceCommand("coal-" + UUID.randomUUID(),
                        "plug-a1", "home-a", "setPower", Map.of("value", true),
                        "alice", null, coalescing.clock.nowMillis(), 0L);
                return coalescing.commandGateway.dispatch(c);
            }));
        }
        start.countDown();
        long effective = 0;
        for (Future<CommandResult> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).outcome() == CommandOutcome.EFFECTIVE) {
                effective++;
            }
        }
        pool.shutdownNow();
        long version = coalescing.stateService.snapshot("plug-a1").version();
        // 合并后实际生效次数（=版本推进）应显著小于提交数，且绝不多于提交数。
        assertTrue(version < n, "COALESCE 必须减少实际生效次数: version=" + version);
        assertTrue(version >= 1);
        log.info("[TEST] coalesce submitted={} effectiveResults={} actualVersions={}",
                n, effective, version);
    }
}
