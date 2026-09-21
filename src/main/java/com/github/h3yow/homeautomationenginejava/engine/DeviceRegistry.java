package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.Home;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家庭、住户、设备的注册表与设备状态持有者。
 *
 * <p>全部使用并发结构；设备初始状态用模型默认值确定性地填充，
 * 使旧版本设备“缺失字段”具备已知默认语义，而非 null。
 */
@Component
public class DeviceRegistry {

    private final Map<String, Home> homes = new ConcurrentHashMap<>();
    private final Map<String, Resident> residents = new ConcurrentHashMap<>();
    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();
    private final Map<String, DeviceModel> models = new ConcurrentHashMap<>();
    private final Map<String, String> deviceHome = new ConcurrentHashMap<>();

    private final EngineClock clock;

    public DeviceRegistry(EngineClock clock) {
        this.clock = clock;
    }

    public void registerHome(Home home) {
        if (home == null || home.homeId() == null) {
            throw new IllegalArgumentException("home and homeId required");
        }
        homes.put(home.homeId(), home);
    }

    public void registerResident(Resident resident) {
        if (resident == null || resident.residentId() == null) {
            throw new IllegalArgumentException("resident required");
        }
        if (!homes.containsKey(resident.homeId())) {
            throw new IllegalArgumentException("unknown home: " + resident.homeId());
        }
        residents.put(resident.residentId(), resident);
    }

    /**
     * 注册设备：必须归属一个已存在的家庭；初始属性取模型默认值快照。
     */
    public void registerDevice(String deviceId, String homeId, DeviceModel model, boolean online) {
        if (deviceId == null || homeId == null || model == null) {
            throw new IllegalArgumentException("deviceId/homeId/model required");
        }
        if (!homes.containsKey(homeId)) {
            throw new IllegalArgumentException("unknown home: " + homeId);
        }
        models.put(deviceId, model);
        deviceHome.put(deviceId, homeId);
        // 初始版本 0：服务端首次接受的上报将前进到 >=1。
        DeviceState state = new DeviceState(deviceId, homeId, model.modelId(),
                online, 0L, clock.nowMillis(), new java.util.LinkedHashMap<>(
                        model.propertyDefaults()));
        devices.put(deviceId, state);
    }

    public boolean exists(String deviceId) {
        return devices.containsKey(deviceId);
    }

    public DeviceModel modelOf(String deviceId) {
        return models.get(deviceId);
    }

    /** 活跃状态对象（仅供 StateService 在串行区内写、其余组件通过 snapshot 读）。 */
    public DeviceState stateOf(String deviceId) {
        return devices.get(deviceId);
    }

    public String homeOfDevice(String deviceId) {
        return deviceHome.get(deviceId);
    }

    public Resident resident(String residentId) {
        return residents.get(residentId);
    }

    /** 返回该家庭设备状态的不可变快照集合（确定性的设备 ID 顺序）。 */
    public List<DeviceState> devicesInHome(String homeId) {
        return devices.values().stream()
                .filter(s -> s.homeId().equals(homeId))
                .map(DeviceState::snapshot)
                .sorted(java.util.Comparator.comparing(DeviceState::deviceId))
                .toList();
    }
}
