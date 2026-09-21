package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备登记处（内存）。所有读写均按设备维度加锁，保证并发入口下的一致视图。
 */
@Service
public class DeviceRegistry {

    public void register(Device device) {
        // TODO 骨架
    }

    public Device get(String deviceId) {
        // TODO 骨架
        return null;
    }

    public List<Device> listByHousehold(String householdId) {
        // TODO 骨架
        return List.of();
    }
}
