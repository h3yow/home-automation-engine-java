package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.BackpressurePolicy;
import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 每设备单工作线程命令队列。
 *
 * 保证：
 * - 同一设备的命令严格按入队顺序执行（FIFO），顺序可预期、无并发半更新；
 * - 队列有界（容量可配置），禁止无界堆积；
 * - REJECT：满了立即拒绝（STORM_REJECTED）；
 * - DELAY：限时等待空位，超时仍满则拒绝（STORM_DELAY_REJECTED）；
 * - MERGE：若同设备同命令的请求已在队，则就地合并参数（后请求并入先请求），
 *   无同类可合并且队列满时退化为 REJECT。
 */
@Service
public class PerDeviceCommandQueue {

    private static final Logger log = LoggerFactory.getLogger(PerDeviceCommandQueue.class);

    public interface CommandHandler {
        void handle(CommandRequest request);
    }

    public record EnqueueResult(
            boolean accepted,
            RejectReason rejectReason,
            String detail,
            String mergedIntoCommandId
    ) {
        static EnqueueResult ok() {
            return new EnqueueResult(true, null, null, null);
        }
    }

    private static final class Lane {
        final LinkedBlockingQueue<CommandRequest> queue;
        final ConcurrentMap<String, String> mergedInto = new ConcurrentHashMap<>();

        Lane(int capacity) {
            this.queue = new LinkedBlockingQueue<>(capacity);
        }
    }

    private final ConcurrentMap<String, Lane> lanes = new ConcurrentHashMap<>();
    private final EngineProperties properties;

    public PerDeviceCommandQueue(EngineProperties properties) {
        this.properties = properties;
    }

    public void start(String deviceId, CommandHandler handler) {
        lanes.compute(deviceId, (id, existing) -> {
            if (existing != null) {
                return existing;
            }
            Lane lane = new Lane(properties.getPerDeviceQueueCapacity());
            Thread worker = new Thread(() -> runLoop(id, lane, handler),
                    "device-worker-" + id);
            worker.setDaemon(true);
            worker.start();
            log.info("[命令队列] 设备 {} 工作线程已启动", id);
            return lane;
        });
    }

    private void runLoop(String deviceId, Lane lane, CommandHandler handler) {
        while (!Thread.currentThread().isInterrupted()) {
            CommandRequest request;
            try {
                synchronized (lane) {
                    while (lane.queue.isEmpty()) {
                        lane.wait();
                    }
                    request = lane.queue.poll();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                handler.handle(request);
            } catch (RuntimeException ex) {
                log.error("[命令队列] 设备 {} 工作线程处理异常", deviceId, ex);
            }
        }
    }

    public EnqueueResult submit(String deviceId, CommandRequest request) {
        Lane lane = lanes.get(deviceId);
        if (lane == null) {
            return new EnqueueResult(false, RejectReason.UNKNOWN_DEVICE,
                    "设备工作线程未启动: " + deviceId, null);
        }
        BackpressurePolicy policy = properties.getBackpressurePolicy();

        if (policy == BackpressurePolicy.MERGE) {
            synchronized (lane) {
                CommandRequest mergeTarget = findMergeTarget(lane, request);
                if (mergeTarget != null) {
                    // 用新参数就地替换在队命令（保持其 commandId 与排队位置），后命令并入
                    replaceQueued(lane, mergeTarget, request.params());
                    lane.mergedInto.put(request.commandId(), mergeTarget.commandId());
                    log.info("[风暴策略-MERGE] 新命令 {} 合并入在队命令 {}，参数更新为 {}",
                            request.commandId(), mergeTarget.commandId(), request.params());
                    lane.notifyAll();
                    return new EnqueueResult(true, null, null, mergeTarget.commandId());
                }
                if (!lane.queue.offer(request)) {
                    return stormRejected(deviceId, request);
                }
                lane.notifyAll();
                return EnqueueResult.ok();
            }
        }

        if (policy == BackpressurePolicy.DELAY) {
            try {
                boolean offered = lane.queue.offer(request,
                        properties.getMaxDelayMillis(), TimeUnit.MILLISECONDS);
                if (!offered) {
                    return new EnqueueResult(false, RejectReason.STORM_REJECTED,
                            "延迟 " + properties.getMaxDelayMillis()
                                    + "ms 后队列仍满，命令被拒绝: " + request.commandId(),
                            null);
                }
                synchronized (lane) {
                    lane.notifyAll();
                }
                return EnqueueResult.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new EnqueueResult(false, RejectReason.STORM_DELAY_REJECTED,
                        "等待入队期间被中断", null);
            }
        }

        // REJECT
        synchronized (lane) {
            if (!lane.queue.offer(request)) {
                return stormRejected(deviceId, request);
            }
            lane.notifyAll();
        }
        return EnqueueResult.ok();
    }

    private void replaceQueued(Lane lane, CommandRequest target,
                               java.util.Map<String, Object> mergedParams) {
        lane.queue.remove(target);
        CommandRequest replaced = new CommandRequest(
                target.commandId(), target.deviceId(), target.householdId(),
                target.command(), java.util.Map.copyOf(mergedParams),
                target.idempotencyKey(), target.sourceRuleId(),
                target.deadlineAfterMillis());
        lane.queue.offer(replaced);
    }

    public String mergedInto(String deviceId, String commandId) {
        Lane lane = lanes.get(deviceId);
        return lane == null ? null : lane.mergedInto.get(commandId);
    }

    public int pendingCount(String deviceId) {
        Lane lane = lanes.get(deviceId);
        return lane == null ? 0 : lane.queue.size();
    }

    /** 测试隔离：清空指定设备当前排队但尚未执行的命令。 */
    public void clearPending(String deviceId) {
        Lane lane = lanes.get(deviceId);
        if (lane != null) {
            synchronized (lane) {
                lane.queue.clear();
                lane.mergedInto.clear();
                lane.notifyAll();
            }
        }
    }

    public void shutdown() {
        // 守护线程随 JVM 退出，这里无需额外动作
    }

    private EnqueueResult stormRejected(String deviceId, CommandRequest request) {
        String detail = "设备 " + deviceId + " 待执行队列已达上限 "
                + properties.getPerDeviceQueueCapacity() + "，命令 "
                + request.commandId() + " 被 REJECT 策略拒绝";
        log.warn("[风暴策略-REJECT] {}", detail);
        return new EnqueueResult(false, RejectReason.STORM_REJECTED, detail, null);
    }

    private CommandRequest findMergeTarget(Lane lane, CommandRequest incoming) {
        Iterator<CommandRequest> it = lane.queue.iterator();
        while (it.hasNext()) {
            CommandRequest queued = it.next();
            if (queued.command().equals(incoming.command())) {
                return queued;
            }
        }
        return null;
    }

    /** 仅供测试/监控：当前队列中的命令快照。 */
    public Map<String, Integer> laneSizes() {
        java.util.Map<String, Integer> sizes = new java.util.HashMap<>();
        lanes.forEach((id, lane) -> sizes.put(id, lane.queue.size()));
        return Map.copyOf(sizes);
    }
}
