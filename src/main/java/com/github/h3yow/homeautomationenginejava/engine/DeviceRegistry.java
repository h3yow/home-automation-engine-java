package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 设备登记处（内存）。读取视图稳定有序，保证跨入口结果一致。
 */
@Service
public class DeviceRegistry {

    private final ConcurrentMap<String, Device> devices = new ConcurrentHashMap<>();

    public void register(Device device) {
        devices.put(device.deviceId(), device);
    }

    public Device get(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("UNKNOWN_DEVICE: 设备不存在 " + deviceId);
        }
        return device;
    }

    public Device find(String deviceId) {
        return devices.get(deviceId);
    }

    public List<Device> listByHousehold(String householdId) {
        return devices.values().stream()
                .filter(d -> d.householdId().equals(householdId))
                .sorted(Comparator.comparing(Device::deviceId))
                .toList();
    }
}
