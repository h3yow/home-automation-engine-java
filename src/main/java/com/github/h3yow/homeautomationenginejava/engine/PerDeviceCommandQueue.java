package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.springframework.stereotype.Service;

/**
 * 每设备单工作线程命令队列：
 * - 保证同一设备命令按入队顺序串行执行（顺序可预期，无半更新/重复执行）；
 * - 队列有界，禁止无界堆积；按配置策略 REJECT/DELAY/MERGE 处理风暴；
 * - 不同设备使用各自工作线程，互不阻塞。
 */
@Service
public class PerDeviceCommandQueue {

    public interface CommandHandler {
        void handle(CommandRequest request);
    }

    public record EnqueueResult(
            boolean accepted,
            RejectReason rejectReason,
            String detail,
            String mergedIntoCommandId
    ) {
    }

    public void start(String deviceId, CommandHandler handler) {
        // TODO 骨架
    }

    public EnqueueResult submit(String deviceId, CommandRequest request) {
        // TODO 骨架
        return null;
    }

    public int pendingCount(String deviceId) {
        // TODO 骨架
        return 0;
    }

    public void shutdown() {
        // TODO 骨架
    }
}
