package com.github.h3yow.homeautomationenginejava.sim;

import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟设备，替代真实硬件。每设备独立配置：延迟、剩余失败次数、在线态。
 *
 * <p>确定性语义：
 * <ul>
 *   <li>离线 -> OFFLINE，绝不产生状态变化（网关记 REJECTED，设备离线有专门原因）；</li>
 *   <li>failRemaining>0 -> FAILED 并递减计数，绝不产生状态变化，失败保留诊断详情；</li>
 *   <li>delayMillis>0 -> 阻塞指定毫秒，用于配合网关超时判定 TIMEOUT；</li>
 *   <li>成功 -> 返回命令期望属性：把布尔/数值/字符串类参数映射为同名状态属性，
 *       其中 "value" 是通用载荷键，映射到设备主属性 power/brightness/targetTemperature
 *       的确定规则见 {@link #deriveTargetProperties}。</li>
 * </ul>
 */
@Component
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    private final DeviceRegistry registry;
    private final Map<String, SimConfigEntry> configs = new ConcurrentHashMap<>();

    public DeviceSimulator(DeviceRegistry registry) {
        this.registry = registry;
    }

    /** 配置设备行为（测试用）。 */
    public void configure(String deviceId, SimConfig config) {
        configs.put(deviceId, new SimConfigEntry(config));
    }

    /** 将设备置为离线/在线（模拟掉线），不触碰失败计数与延迟。 */
    public void setOnline(String deviceId, boolean online) {
        SimConfigEntry entry = configs.computeIfAbsent(deviceId,
                k -> new SimConfigEntry(SimConfig.defaultOnline()));
        entry.online = online;
        log.info("[SIM] device={} online={}", deviceId, online);
    }

    /**
     * 同步执行一条已通过模型校验的命令。
     */
    public SimResponse execute(DeviceCommand command) {
        String deviceId = command.deviceId();
        SimConfigEntry entry = configs.get(deviceId);
        boolean online = entry == null || entry.online;
        long delay = entry == null ? 0L : entry.delayMillis;

        if (!online) {
            log.info("[SIM] device={} OFFLINE reject command={} id={}",
                    deviceId, command.command(), command.commandId());
            return new SimResponse(SimStatus.OFFLINE, Map.of(), "device offline");
        }
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SimResponse(SimStatus.FAILED, Map.of(), "interrupted");
            }
        }
        if (entry != null && entry.failRemaining.get() > 0) {
            int left = entry.failRemaining.decrementAndGet();
            log.info("[SIM] device={} FAILED command={} id={} failRemaining={}",
                    deviceId, command.command(), command.commandId(), left);
            return new SimResponse(SimStatus.FAILED, Map.of(),
                    "simulated execution failure (remaining after this=" + left + ")");
        }

        Map<String, Object> target = deriveTargetProperties(command);
        log.info("[SIM] device={} OK command={} id={} target={}",
                deviceId, command.command(), command.commandId(), target);
        return new SimResponse(SimStatus.OK, target, "executed");
    }

    /**
     * 命令 -> 目标属性的确定映射（不依赖真实设备协议）：
     * 1) 命令名即属性名（如 setPower 对应 power，setBrightness 对应 brightness）：
     *    参数 value 映射到去掉 set 前缀并首字母小写的属性；
     * 2) 其余参数按其键名映射为同名属性（前提是模型已声明，网关会再校验）。
     */
    private Map<String, Object> deriveTargetProperties(DeviceCommand command) {
        Map<String, Object> target = new LinkedHashMap<>();
        String cmd = command.command();
        if (cmd != null && cmd.startsWith("set") && cmd.length() > 3
                && command.params().containsKey("value")) {
            String attr = Character.toLowerCase(cmd.charAt(3)) + cmd.substring(4);
            target.put(attr, command.params().get("value"));
        }
        command.params().forEach((k, v) -> {
            if (!"value".equals(k)) {
                target.put(k, v);
            }
        });
        return target;
    }

    public enum SimStatus { OK, FAILED, OFFLINE }

    public record SimResponse(SimStatus status, Map<String, Object> targetProperties,
                              String detail) {
    }

    public record SimConfig(long delayMillis, int failRemaining, boolean online) {
        public static SimConfig defaultOnline() { return new SimConfig(0L, 0, true); }
    }

    private static final class SimConfigEntry {
        final long delayMillis;
        final AtomicInteger failRemaining;
        volatile boolean online;

        SimConfigEntry(SimConfig config) {
            this.delayMillis = config.delayMillis();
            this.failRemaining = new AtomicInteger(config.failRemaining());
            this.online = config.online();
        }
    }
}
