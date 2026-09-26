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
    /** 试运行推演最大链路层数（BFS 深度上限，防止无界传播）。 */
    private int dryRunMaxDepth = 16;
    /** 试运行推演最大访问节点数（事件 + 规则评估总步数上限）。 */
    private int dryRunMaxVisitedNodes = 512;
    /** 试运行报告最大返回条目数（判定步骤数上限，超出仅截断报告不终止推演）。 */
    private int dryRunMaxReportEntries = 200;

    // TODO 骨架 getter/setter
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
    public int getDryRunMaxVisitedNodes() { return dryRunMaxVisitedNodes; }
    public void setDryRunMaxVisitedNodes(int v) { this.dryRunMaxVisitedNodes = v; }
    public int getDryRunMaxReportEntries() { return dryRunMaxReportEntries; }
    public void setDryRunMaxReportEntries(int v) { this.dryRunMaxReportEntries = v; }
}
