package com.github.h3yow.homeautomationenginejava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备运行态。version 为单调递增版本号，用于乱序/重复上报的收敛判定。
 *
 * <p>写操作仅由 StateService 在每设备串行区内调用（{@link #apply}），
 * 属性容器使用 {@link ConcurrentHashMap}，使任意时刻的跨线程读都不会看到
 * 结构性损坏；{@link #snapshot()} 给出一致性只读拷贝，规则只读已提交快照。
 */
public final class DeviceState {

    private final String deviceId;
    private final String homeId;
    private final String modelId;
    private volatile boolean online;
    private volatile long version;
    private volatile long updatedAtMillis;
    private volatile String lastAppliedCommandId;
    private final Map<String, Object> properties;

    public DeviceState(String deviceId, String homeId, String modelId,
                       boolean online, long version, long updatedAtMillis,
                       Map<String, Object> properties) {
        this.deviceId = deviceId;
        this.homeId = homeId;
        this.modelId = modelId;
        this.online = online;
        this.version = version;
        this.updatedAtMillis = updatedAtMillis;
        this.properties = new ConcurrentHashMap<>(
                properties == null ? Map.of() : properties);
    }

    private DeviceState(String deviceId, String homeId, String modelId,
                        boolean online, long version, long updatedAtMillis,
                        String lastAppliedCommandId, Map<String, Object> frozen) {
        this.deviceId = deviceId;
        this.homeId = homeId;
        this.modelId = modelId;
        this.online = online;
        this.version = version;
        this.updatedAtMillis = updatedAtMillis;
        this.lastAppliedCommandId = lastAppliedCommandId;
        this.properties = frozen;
    }

    public String deviceId() { return deviceId; }
    public String homeId() { return homeId; }
    public String modelId() { return modelId; }
    public boolean online() { return online; }
    public long version() { return version; }
    public long updatedAtMillis() { return updatedAtMillis; }
    public String lastAppliedCommandId() { return lastAppliedCommandId; }
    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Object property(String name) {
        return properties.get(name);
    }

    /**
     * 在调用方持有该设备串行锁时应用一次状态变更。
     *
     * @param newVersion 必须大于当前版本（调用方保证），单调前进
     * @param online     null 表示本次不更新在线态
     * @param patch      已通过模型校验的已知属性增量（null 表示无属性更新）
     * @param commandId  非空时记录为最后生效命令（幂等生效确认用）
     */
    public void apply(long newVersion, Boolean online, Map<String, Object> patch,
               String commandId, long nowMillis) {
        if (online != null) {
            this.online = online;
        }
        if (patch != null && !patch.isEmpty()) {
            this.properties.putAll(patch);
        }
        this.version = newVersion;
        this.updatedAtMillis = nowMillis;
        if (commandId != null) {
            this.lastAppliedCommandId = commandId;
        }
    }

    /**
     * 生成不可变一致性快照：规则求值与跨设备读取只允许使用快照，
     * 绝不读写入中的半更新状态。
     */
    public DeviceState snapshot() {
        Map<String, Object> frozen =
                Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        return new DeviceState(deviceId, homeId, modelId, online, version,
                updatedAtMillis, lastAppliedCommandId, frozen);
    }
}
