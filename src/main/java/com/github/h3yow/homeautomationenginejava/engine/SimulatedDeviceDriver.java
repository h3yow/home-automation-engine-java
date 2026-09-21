package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地模拟硬件驱动。
 * - HEALTHY：短暂模拟耗时后执行成功，结果属性由“命令目标属性 + 参数值”确定；
 * - OFFLINE：直接返回离线失败（不产生任何副作用）；
 * - FAIL_ALWAYS：设备返回执行失败；
 * - NO_RESPONSE：阻塞等待中断（由命令超时机制中断），结论为 TIMEOUT；
 * - 同一幂等键的重复执行只回放首次结论，保证重试无重复副作用。
 */
@Service
public class SimulatedDeviceDriver {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDeviceDriver.class);

    public enum FaultMode { HEALTHY, OFFLINE, FAIL_ALWAYS, NO_RESPONSE }

    public record DriverOutcome(boolean success, RejectReason failureReason,
                                Map<String, Object> resultingAttributes, long seq) {
    }

    private final ConcurrentMap<String, FaultMode> faults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, DriverOutcome>> idempotency =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> hardwareCounters = new ConcurrentHashMap<>();
    private final ModelRegistry modelRegistry;
    private final EngineClock clock;

    public SimulatedDeviceDriver(ModelRegistry modelRegistry, EngineClock clock) {
        this.modelRegistry = modelRegistry;
        this.clock = clock;
    }

    public void setFault(String deviceId, FaultMode mode) {
        faults.put(deviceId, mode);
        log.info("[故障注入] device={} mode={}", deviceId, mode);
    }

    public FaultMode fault(String deviceId) {
        return faults.getOrDefault(deviceId, FaultMode.HEALTHY);
    }

    public DriverOutcome execute(Device device, CommandRequest request) {
        FaultMode mode = fault(device.deviceId());
        if (mode == FaultMode.OFFLINE) {
            log.warn("[驱动-失败] device={} command={} 原因=DEVICE_OFFLINE",
                    device.deviceId(), request.command());
            return new DriverOutcome(false, RejectReason.DEVICE_OFFLINE, Map.of(), -1);
        }
        if (mode == FaultMode.FAIL_ALWAYS) {
            log.warn("[驱动-失败] device={} command={} 原因=EXECUTION_FAILED(设备返回失败)",
                    device.deviceId(), request.command());
            return new DriverOutcome(false, RejectReason.EXECUTION_FAILED, Map.of(), -1);
        }

        ConcurrentMap<String, DriverOutcome> perDevice =
                idempotency.computeIfAbsent(device.deviceId(), k -> new ConcurrentHashMap<>());
        DriverOutcome cached = perDevice.get(request.idempotencyKey());
        if (cached != null) {
            log.info("[驱动-幂等回放] device={} command={} idemKey={} success={}",
                    device.deviceId(), request.command(), request.idempotencyKey(),
                    cached.success());
            return cached;
        }

        if (mode == FaultMode.NO_RESPONSE) {
            log.warn("[驱动-无响应] device={} command={} 工作线程阻塞等待超时中断",
                    device.deviceId(), request.command());
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[驱动-超时中断] device={} command={}",
                        device.deviceId(), request.command());
                return new DriverOutcome(false, RejectReason.NO_RESPONSE, Map.of(), -1);
            }
        }

        simulateLatency(request.deadlineAfterMillis());

        DeviceModel model = modelRegistry.modelFor(device);
        String targetAttr = model.commandTarget().get(request.command());
        Object value = request.params().get("value");
        if (value == null) {
            value = model.commandResult().get(request.command());
        }
        Map<String, Object> resulting =
                (targetAttr == null || value == null) ? Map.of()
                        : Map.of(targetAttr, value);
        long seq = hardwareCounters.merge(device.deviceId(), 1L, Long::sum);
        DriverOutcome outcome = new DriverOutcome(true, null,
                Map.copyOf(resulting), seq);
        perDevice.put(request.idempotencyKey(), outcome);
        log.info("[驱动-成功] device={} command={} idemKey={} 结果属性={} hwSeq={}",
                device.deviceId(), request.command(), request.idempotencyKey(),
                resulting, seq);
        return outcome;
    }

    private void simulateLatency(long deadlineAfterMillis) {
        // 健康设备固定 20ms 模拟耗时；配置超时过短时随之缩短，保证单测快速可重复
        long latency = Math.min(20L, Math.max(1L, deadlineAfterMillis / 10));
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long now() {
        return clock.nowMillis();
    }
}
