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
    /** 试运行：单次传播最大链路层数（超出即截断）。 */
    private int dryRunMaxDepth = 16;
    /** 试运行：整场推演可访问的事件节点数上限（超出即截断）。 */
    private int dryRunMaxEvents = 256;
    /** 试运行：报告返回的判定步骤条目数上限（超出只截断返回，不中断推演）。 */
    private int dryRunMaxSteps = 500;

    public int getPerDeviceQueueCapacity() { return perDeviceQueueCapacity; }
    public void setPerDeviceQueueCapacity(int v) { this.perDeviceQueueCapacity = v; }
    public BackpressurePolicy getBackpressurePolicy() { return backpressurePolicy; }
    public void setBackpressurePolicy(BackpressurePolicy v) { this.backpressurePolicy = v; }
    public long getMaxDelayMillis() { return maxDelayMillis; }
    public void setMaxDelayMillis(long v) { this.maxDelayMillis = v; }
    public long getCommandTimeoutMillis() { return commandTimeoutMillis; }
    public void setCommandTimeoutMillis(long v) { this.commandTimeoutMillis = v; }
    public int getDryRunMaxDepth() { return dryRunMaxDepth; }
    public void setDryRunMaxDepth(int v) { this.dryRunMaxDepth = v; }
    public int getDryRunMaxEvents() { return dryRunMaxEvents; }
    public void setDryRunMaxEvents(int v) { this.dryRunMaxEvents = v; }
    public int getDryRunMaxSteps() { return dryRunMaxSteps; }
    public void setDryRunMaxSteps(int v) { this.dryRunMaxSteps = v; }
}
