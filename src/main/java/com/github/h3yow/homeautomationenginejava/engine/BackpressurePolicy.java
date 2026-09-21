package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 命令风暴保护（每设备有界）：
 * <ul>
 *   <li>REJECT（默认）：队列已满立即拒绝 BACKPRESSURE_REJECTED，绝不无界堆积；</li>
 *   <li>DELAY：由网关在容量满时最多等待 delayMillis，仍满则拒绝（不无限等待）；</li>
 *   <li>COALESCE：同设备同命令且参数一致的排队项由网关合并为一个（共享 commandId
 *       去重语义），合并后仍超限则拒绝。</li>
 * </ul>
 * 决胜与时间精度无关：容量/模式均为配置值，判定只看当前队列长度。
 */
@Component
public class BackpressurePolicy {

    public enum Mode { REJECT, DELAY, COALESCE }

    private final int maxQueuePerDevice;
    private final long delayMillis;
    private final Mode mode;

    public BackpressurePolicy(
            @Value("${ha.backpressure.max-queue-per-device:8}") int maxQueuePerDevice,
            @Value("${ha.backpressure.delay-millis:200}") long delayMillis,
            @Value("${ha.backpressure.mode:REJECT}") String mode) {
        this.maxQueuePerDevice = maxQueuePerDevice;
        this.delayMillis = delayMillis;
        this.mode = Mode.valueOf(mode);
    }

    public int maxQueuePerDevice() { return maxQueuePerDevice; }
    public long delayMillis() { return delayMillis; }
    public Mode mode() { return mode; }

    /**
     * 准入判定。
     *
     * @param currentQueued 该设备当前排队（含正在执行）的命令数
     * @return null 表示准入；否则给出拒绝原因（容量满）
     */
    public RejectReason admission(int currentQueued) {
        if (currentQueued >= maxQueuePerDevice) {
            return RejectReason.BACKPRESSURE_REJECTED;
        }
        return null;
    }

    public String describe() {
        return "BackpressurePolicy{mode=" + mode + ", maxQueuePerDevice="
                + maxQueuePerDevice + ", delayMillis=" + delayMillis + "}";
    }
}
