package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 设备模型登记处：按 modelId 维护各版本能力模型。
 * 版本裁决（确定、不猜测）：
 * 1) 精确版本命中 -> 采用；
 * 2) 未登记 -> 回退到不高于该版本的最新登记版本（旧模型对新固件保持前向兼容假设），
 *    但调用方仍需对“未知能力/未知命令”做拒绝处理；
 * 3) 不存在任何 <= 该版本的登记模型 -> 抛出版本不支持，明确拒绝。
 */
@Service
public class ModelRegistry {

    private final ConcurrentMap<String, ConcurrentMap<Integer, DeviceModel>> models =
            new ConcurrentHashMap<>();

    public void registerModel(DeviceModel model) {
        models.computeIfAbsent(model.modelId(), k -> new ConcurrentHashMap<>())
                .put(model.version(), model);
    }

    public DeviceModel resolveModel(String modelId, int version) {
        ConcurrentMap<Integer, DeviceModel> versions = models.get(modelId);
        if (versions == null) {
            throw new IllegalArgumentException("UNSUPPORTED_MODEL_VERSION: 未知模型 " + modelId);
        }
        DeviceModel exact = versions.get(version);
        if (exact != null) {
            return exact;
        }
        Optional<DeviceModel> fallback = versions.entrySet().stream()
                .filter(e -> e.getKey() <= version)
                .max(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue);
        return fallback.orElseThrow(() -> new IllegalArgumentException(
                "UNSUPPORTED_MODEL_VERSION: 模型 " + modelId + " 版本 " + version
                        + " 无兼容登记版本"));
    }

    public DeviceModel modelFor(Device device) {
        return resolveModel(device.modelId(), device.modelVersion());
    }
}
