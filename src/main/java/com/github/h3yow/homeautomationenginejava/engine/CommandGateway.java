package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.sim.DeviceSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 命令下发与状态收敛。
 *
 * <p>并发模型（无死锁、顺序可预期）：
 * <ul>
 *   <li>每个设备一条 FIFO 命令队列 + 一个“排空链”：同一时刻只有一个排空任务
 *       执行该设备队首命令，完成后才投递下一个排空任务，因此<b>同一设备的命令
 *       严格按入队顺序执行</b>；不同设备的排空任务在共享有界池上并行；</li>
 *   <li>状态写入只在 StateService 的每设备串行区发生，排空任务本身不长期持锁，
 *       规则链在独立传播线程中推进，故跨设备命令互不构成循环等待；</li>
 *   <li>在途计数（排队+执行）受 {@link BackpressurePolicy} 容量约束，有界拒绝/延迟/合并；</li>
 *   <li>终态由 ACCEPTED 迁移且仅一次；重复 commandId（重试/合并）共享同一终态，
 *       不重复执行、不产生重复副作用；</li>
 *   <li>无响应超时 -> TIMEOUT；设备失败 -> FAILED；离线 -> REJECTED/DEVICE_OFFLINE；
 *       离线与失败绝不写成功。</li>
 * </ul>
 */
@Component
public class CommandGateway {

    private static final Logger log = LoggerFactory.getLogger(CommandGateway.class);

    private final DeviceRegistry registry;
    private final StateService stateService;
    private final ModelValidator modelValidator;
    private final BackpressurePolicy backpressurePolicy;
    private final AuditLog auditLog;
    private final EngineClock clock;
    private final DeviceSimulator simulator;

    private final Map<String, ConcurrentLinkedQueue<Entry>> deviceQueues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Map<String, Pending> pendingByCoalesceKey = new ConcurrentHashMap<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** 自动化命令 ID -> 所属规则传播 ID（由规则引擎派发前登记）。 */
    private final Map<String, String> commandPropagation = new ConcurrentHashMap<>();

    /** 规则引擎在派发自动化命令前登记其传播归属。 */
    public void attachPropagation(String commandId, String propagationId) {
        commandPropagation.put(commandId, propagationId);
    }

    /** 命令执行池：有界，CallerRuns 反压，绝不无界堆积线程。 */
    private final ThreadPoolExecutor executionPool;
    /** 模拟器调用池：承载可阻塞的 sleep，便于超时中断。 */
    private final ThreadPoolExecutor simulationPool;

    private volatile long commandTimeoutMillis = 5_000L;

    public CommandGateway(DeviceRegistry registry, StateService stateService,
                          ModelValidator modelValidator, BackpressurePolicy backpressurePolicy,
                          AuditLog auditLog, EngineClock clock, DeviceSimulator simulator) {
        this.registry = registry;
        this.stateService = stateService;
        this.modelValidator = modelValidator;
        this.backpressurePolicy = backpressurePolicy;
        this.auditLog = auditLog;
        this.clock = clock;
        this.simulator = simulator;
        this.executionPool = new ThreadPoolExecutor(4, 16, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), daemonFactory("ha-exec-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.simulationPool = new ThreadPoolExecutor(4, 32, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), daemonFactory("ha-sim-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** 测试配置：设备执行等待上限。 */
    public void setCommandTimeoutMillis(long millis) { this.commandTimeoutMillis = millis; }

    public CommandResult dispatch(DeviceCommand command) {
        if (command == null || command.commandId() == null) {
            String deviceId = command == null ? "unknown" : command.deviceId();
            return new CommandResult(null, deviceId, CommandOutcome.REJECTED,
                    RejectReason.INVALID_PAYLOAD, "command/commandId required",
                    -1L, clock.nowMillis());
        }
        // 幂等去重：同一 commandId 的重复提交（含重试）共享同一终态，绝不重复执行。
        Entry existing = entries.get(command.commandId());
        if (existing != null) {
            log.info("[CMD] duplicate commandId={} -> reuse existing result", command.commandId());
            return awaitEntry(existing);
        }

        if (!registry.exists(command.deviceId())) {
            return finishReject(new Entry(command), RejectReason.DEVICE_NOT_FOUND,
                    "device not registered: " + command.deviceId());
        }
        String boundHome = registry.homeOfDevice(command.deviceId());
        if (command.homeId() == null || !boundHome.equals(command.homeId())) {
            return finishReject(new Entry(command), RejectReason.CROSS_HOME_ACCESS,
                    "command home '" + command.homeId()
                            + "' does not match device home '" + boundHome + "'");
        }

        ModelValidator.Validation validation = modelValidator.validateCommand(
                command.deviceId(), command.command(), command.params());
        if (!validation.ok()) {
            return finishReject(new Entry(command), validation.reason(), validation.detail());
        }
        DeviceCommand normalized = new DeviceCommand(command.commandId(), command.deviceId(),
                command.homeId(), command.command(), validation.normalized(),
                command.issuedBy(), command.sourceRuleId(), command.issuedAtMillis(),
                command.dedupKey());

        DeviceState state = stateService.snapshot(command.deviceId());
        if (state != null && !state.online()) {
            return finishReject(new Entry(normalized), RejectReason.DEVICE_OFFLINE,
                    "device offline at dispatch: " + command.deviceId());
        }

        // COALESCE：同设备同命令同归一化参数的在途请求合并共享终态。
        if (backpressurePolicy.mode() == BackpressurePolicy.Mode.COALESCE) {
            String key = coalesceKey(normalized);
            Pending present = pendingByCoalesceKey.get(key);
            if (present != null) {
                log.info("[CMD] coalesced commandId={} onto key={}", command.commandId(), key);
                return awaitEntry(present.entry);
            }
        }

        AtomicInteger counter = inflight.computeIfAbsent(command.deviceId(),
                k -> new AtomicInteger());
        if (backpressurePolicy.admission(counter.get()) != null
                && backpressurePolicy.mode() == BackpressurePolicy.Mode.DELAY) {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(backpressurePolicy.delayMillis());
            while (backpressurePolicy.admission(counter.get()) != null
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (backpressurePolicy.admission(counter.get()) != null) {
            return finishReject(new Entry(normalized), RejectReason.BACKPRESSURE_REJECTED,
                    "per-device queue full (" + counter.get() + "/"
                            + backpressurePolicy.maxQueuePerDevice() + ") mode="
                            + backpressurePolicy.mode());
        }

        Entry entry = new Entry(normalized);
        if (entries.putIfAbsent(command.commandId(), entry) != null) {
            return awaitEntry(entries.get(command.commandId()));
        }
        counter.incrementAndGet();

        String key = backpressurePolicy.mode() == BackpressurePolicy.Mode.COALESCE
                ? coalesceKey(normalized) : null;
        if (key != null) {
            pendingByCoalesceKey.put(key, new Pending(entry));
        }

        deviceQueues.computeIfAbsent(command.deviceId(), k -> new ConcurrentLinkedQueue<>())
                .add(entry);
        scheduleDrain(command.deviceId());
        return awaitEntry(entry);
    }

    public CommandResult getResult(String commandId) {
        Entry entry = entries.get(commandId);
        if (entry == null) {
            return null;
        }
        CommandResult snapshot = entry.result;
        return snapshot != null ? snapshot
                : CommandResult.accepted(commandId, entry.command.deviceId(), clock.nowMillis());
    }

    /* ---------------- 有序排空链 ---------------- */

    private void scheduleDrain(String deviceId) {
        AtomicBoolean flag = draining.computeIfAbsent(deviceId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return; // 已有排空任务在运行，它完成后会继续排空
        }
        try {
            executionPool.submit(() -> drain(deviceId, flag));
        } catch (RejectedExecutionException e) {
            flag.set(false);
            // 极端情况下池满且 CallerRuns 也失败：由当前线程直接排空。
            drain(deviceId, flag);
        }
    }

    private void drain(String deviceId, AtomicBoolean flag) {
        try {
            Entry entry;
            while ((entry = deviceQueues.get(deviceId).poll()) != null) {
                runEntry(entry);
            }
        } finally {
            flag.set(false);
            // 双检：入队发生在 flag 置 false 之后可能错过调度，补一次。
            if (!deviceQueues.getOrDefault(deviceId, new ConcurrentLinkedQueue<>()).isEmpty()) {
                scheduleDrain(deviceId);
            }
        }
    }

    private void runEntry(Entry entry) {
        DeviceCommand command = entry.command;
        try {
            Future<DeviceSimulator.SimResponse> f =
                    simulationPool.submit(() -> simulator.execute(command));
            DeviceSimulator.SimResponse response;
            try {
                response = f.get(commandTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                finishTerminal(entry, CommandOutcome.TIMEOUT, null,
                        "no effective response within " + commandTimeoutMillis + "ms",
                        currentVersion(command.deviceId()));
                return;
            } catch (Exception e) {
                finishTerminal(entry, CommandOutcome.FAILED, null,
                        "execution error: " + rootMessage(e), currentVersion(command.deviceId()));
                return;
            }

            switch (response.status()) {
                case OFFLINE -> finishReject(entry, RejectReason.DEVICE_OFFLINE,
                        "device reported offline during execution");
                case FAILED -> finishTerminal(entry, CommandOutcome.FAILED, null,
                        "device execution failed: " + response.detail(),
                        currentVersion(command.deviceId()));
                case OK -> {
                    DeviceState before = stateService.snapshot(command.deviceId());
                    var confirm = stateService.confirmEffect(command.deviceId(),
                            command.commandId(), before.version() + 1,
                            response.targetProperties(),
                            commandPropagation.get(command.commandId()));
                    if (confirm.accepted()) {
                        finishTerminal(entry, CommandOutcome.EFFECTIVE, null,
                                "state converged: " + response.detail()
                                        + " version=" + confirm.newVersion(),
                                confirm.newVersion());
                    } else {
                        finishTerminal(entry, CommandOutcome.FAILED, confirm.reason(),
                                "effect not confirmed: " + confirm.detail(),
                                confirm.newVersion());
                    }
                }
            }
        } catch (Throwable t) {
            finishTerminal(entry, CommandOutcome.FAILED, null,
                    "unexpected error: " + rootMessage(t), currentVersion(command.deviceId()));
        } finally {
            decrementInflight(command.deviceId());
            if (backpressurePolicy.mode() == BackpressurePolicy.Mode.COALESCE) {
                pendingByCoalesceKey.remove(coalesceKey(command));
            }
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private long currentVersion(String deviceId) {
        DeviceState s = stateService.snapshot(deviceId);
        return s == null ? -1L : s.version();
    }

    private void decrementInflight(String deviceId) {
        AtomicInteger counter = inflight.get(deviceId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    private void finishTerminal(Entry entry, CommandOutcome outcome,
                                RejectReason reason, String detail, long version) {
        CommandResult result = new CommandResult(entry.command.commandId(),
                entry.command.deviceId(), outcome, reason, detail, version, clock.nowMillis());
        // 先留痕、后放行等待者：保证 dispatch 返回时审计已可查（无半完成可见性）。
        if (entry.result == null) {
            entry.result = result;
            audit(result, entry.command);
            entry.futureTerminal.complete(result);
            log.info("[CMD] terminal id={} device={} outcome={} reason={} {}",
                    entry.command.commandId(), entry.command.deviceId(),
                    outcome, reason, detail);
        }
    }

    private CommandResult finishReject(Entry entry, RejectReason reason, String detail) {
        CommandResult result = new CommandResult(entry.command.commandId(),
                entry.command.deviceId(), CommandOutcome.REJECTED, reason, detail,
                currentVersion(entry.command.deviceId()), clock.nowMillis());
        if (entry.result == null) {
            entry.result = result;
            audit(result, entry.command);
            entry.futureTerminal.complete(result);
            log.info("[CMD] rejected id={} device={} reason={} {}",
                    entry.command.commandId(), entry.command.deviceId(), reason, detail);
        }
        return entry.result;
    }

    private void audit(CommandResult result, DeviceCommand command) {
        auditLog.record(new ExecutionRecord(
                "exec-" + auditLog.nextSequence(),
                command.homeId(),
                command.sourceRuleId(),
                command.commandId(),
                command.deviceId(),
                result.outcome(),
                result.rejectReason(),
                (command.isAutomation() ? "[auto:" + command.sourceRuleId() + "] "
                        : "[user:" + command.issuedBy() + "] ") + result.detail(),
                result.atMillis()));
    }

    private CommandResult awaitEntry(Entry entry) {
        try {
            return entry.futureTerminal.get(commandTimeoutMillis + 5_000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return getResult(entry.command.commandId());
        }
    }

    private String coalesceKey(DeviceCommand c) {
        return c.deviceId() + '|' + c.command() + '|'
                + (c.params() == null ? "" : c.params().toString());
    }

    /** 一次命令的完整上下文：终态只允许设置一次（CAS 语义）。 */
    private static final class Entry {
        final DeviceCommand command;
        final CompletableFuture<CommandResult> futureTerminal = new CompletableFuture<>();
        volatile CommandResult result;

        Entry(DeviceCommand command) {
            this.command = command;
        }

        boolean complete(CommandResult r) {
            if (result != null) {
                return false;
            }
            this.result = r;
            futureTerminal.complete(r);
            return true;
        }
    }

    private record Pending(Entry entry) { }
}
