package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.BackpressurePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可配置的引擎策略：单设备队列上限、风暴策略、延迟上限、设备执行超时。
 */
@Component
@ConfigurationProperties(prefix = "homeauto.engine")
public class EngineProperties {

    private int perDeviceQueueCapacity = 8;
    private BackpressurePolicy backpressurePolicy = BackpressurePolicy.REJECT;
    private long maxDelayMillis = 2000;
    private long commandTimeoutMillis = 1500;

    // TODO 骨架 getter/setter
    public int getPerDeviceQueueCapacity() { return perDeviceQueueCapacity; }
    public void setPerDeviceQueueCapacity(int v) { this.perDeviceQueueCapacity = v; }
    public BackpressurePolicy getBackpressurePolicy() { return backpressurePolicy; }
    public void setBackpressurePolicy(BackpressurePolicy v) { this.backpressurePolicy = v; }
    public long getMaxDelayMillis() { return maxDelayMillis; }
    public void setMaxDelayMillis(long v) { this.maxDelayMillis = v; }
    public long getCommandTimeoutMillis() { return commandTimeoutMillis; }
    public void setCommandTimeoutMillis(long v) { this.commandTimeoutMillis = v; }
}
