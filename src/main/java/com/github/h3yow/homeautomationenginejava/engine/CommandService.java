package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 统一命令入口（用户入口与规则内部入口共用同一套校验与执行语义）。
 *
 * 生命周期：鉴权/模型校验 -> 幂等去重 -> 风暴策略入队 -> 每设备工作线程串行执行 ->
 * 驱动执行（超时由独立驱动线程 + join 控制）-> 生效确认与链式规则传播 -> 记录结论。
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    public record SubmitOutcome(CommandResult result, boolean terminal) {
    }

    private record Pending(CommandRequest request, String principalId,
                           CompletableFuture<CommandResult> future) {
    }

    private final ConcurrentMap<String, Pending> pendings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CommandResult> conclusions = new ConcurrentHashMap<>();
    /** 设备|幂等键 -> 首个命令 id：重试只返回同一结论，绝不二次执行。 */
    private final ConcurrentMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    /** 幂等键的细粒度锁：保证“去重判定 + 入队”对同键命令原子，消除并发重复执行。 */
    private final ConcurrentMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    private final DeviceRegistry deviceRegistry;
    private final ModelRegistry modelRegistry;
    private final AccessControl accessControl;
    private final PerDeviceCommandQueue queue;
    private final SimulatedDeviceDriver driver;
    private final StateConvergence stateConvergence;
    private final ExecutionLog executionLog;
    private final EngineProperties properties;
    private final EngineClock clock;
    private final ObjectProvider<RuleEngine> ruleEngineProvider;

    public CommandService(DeviceRegistry deviceRegistry, ModelRegistry modelRegistry,
                          AccessControl accessControl, PerDeviceCommandQueue queue,
                          SimulatedDeviceDriver driver, StateConvergence stateConvergence,
                          ExecutionLog executionLog, EngineProperties properties,
                          EngineClock clock, ObjectProvider<RuleEngine> ruleEngineProvider) {
        this.deviceRegistry = deviceRegistry;
        this.modelRegistry = modelRegistry;
        this.accessControl = accessControl;
        this.queue = queue;
        this.driver = driver;
        this.stateConvergence = stateConvergence;
        this.executionLog = executionLog;
        this.properties = properties;
        this.clock = clock;
        this.ruleEngineProvider = ruleEngineProvider;
    }

    public SubmitOutcome submit(CommandRequest request, String principalId) {
        accessControl.requireDeviceAccess(principalId, request.deviceId());
        return doSubmit(request, principalId);
    }

    public SubmitOutcome submitFromRule(CommandRequest request) {
        accessControl.requireRuleActionScope(request.sourceRuleId(), request.deviceId());
        return doSubmit(request, null);
    }

    private SubmitOutcome doSubmit(CommandRequest request, String principalId) {
        Device device = deviceRegistry.get(request.deviceId());

        // 模型校验在幂等锁外先做（不涉及共享状态竞争）
        try {
            validateAgainstModel(device, request);
        } catch (CommandRejectedException ex) {
            return new SubmitOutcome(terminal(request, device, principalId,
                    CommandOutcome.REJECTED, ex.reason, ex.getMessage(), null), true);
        }

        String idemUnique = request.deviceId() + "|" + request.idempotencyKey();
        Object idemLock = idempotencyLocks.computeIfAbsent(idemUnique, k -> new Object());
        synchronized (idemLock) {
            String existingId = idempotencyIndex.get(idemUnique);
            if (existingId != null) {
                CommandResult existing = conclusions.get(existingId);
                String detail = "幂等键 " + request.idempotencyKey() + " 与命令 "
                        + existingId + " 相同，重复命令不二次执行";
                log.info("[命令-去重] device={} incoming={} existing={} outcome={}",
                        request.deviceId(), request.commandId(), existingId,
                        existing == null ? "PENDING" : existing.outcome());
                CommandResult duplicate = new CommandResult(request.commandId(),
                        request.deviceId(),
                        existing == null ? CommandOutcome.ACCEPTED : existing.outcome(),
                        RejectReason.DUPLICATE_COMMAND, detail, null, existingId,
                        existing == null ? Map.of() : existing.effectiveAttributes(),
                        clock.nowMillis());
                conclusions.put(request.commandId(), duplicate);
                return new SubmitOutcome(duplicate, existing != null);
            }

            StateSnapshot current = stateConvergence.snapshot(request.deviceId());
            if (current != null && !current.online()) {
                return new SubmitOutcome(terminal(request, device, principalId,
                        CommandOutcome.FAILED, RejectReason.DEVICE_OFFLINE,
                        "设备当前离线，命令不执行", null), true);
            }

            CompletableFuture<CommandResult> future = new CompletableFuture<>();
            pendings.put(request.commandId(), new Pending(request, principalId, future));
            PerDeviceCommandQueue.EnqueueResult enqueue =
                    queue.submit(request.deviceId(), request);

            if (!enqueue.accepted()) {
                pendings.remove(request.commandId());
                return new SubmitOutcome(terminal(request, device, principalId,
                        CommandOutcome.REJECTED, enqueue.rejectReason(),
                        enqueue.detail(), null), true);
            }

            if (enqueue.mergedIntoCommandId() != null) {
                CommandResult merged = new CommandResult(request.commandId(),
                        request.deviceId(), CommandOutcome.ACCEPTED, RejectReason.MERGED,
                        "已合并入在队命令 " + enqueue.mergedIntoCommandId(),
                        null, enqueue.mergedIntoCommandId(), Map.of(), clock.nowMillis());
                conclusions.put(request.commandId(), merged);
                log.info("[命令-合并] incoming={} mergedInto={}",
                        request.commandId(), enqueue.mergedIntoCommandId());
                return new SubmitOutcome(merged, false);
            }

            // 入队成功后再登记幂等键，保证同键并发请求只有一个真正入队执行
            idempotencyIndex.put(idemUnique, request.commandId());
            CommandResult accepted = new CommandResult(request.commandId(),
                    request.deviceId(), CommandOutcome.ACCEPTED, null,
                    "命令已受理并进入设备串行队列", null, null, Map.of(), clock.nowMillis());
            conclusions.put(request.commandId(), accepted);
            log.info("[命令-受理] device={} command={} id={} source={}",
                    request.deviceId(), request.command(), request.commandId(),
                    request.sourceRuleId() == null ? "user:" + principalId
                            : "rule:" + request.sourceRuleId());
            return new SubmitOutcome(accepted, false);
        }
    }


    /** 校验命令是否符合设备模型；非法时抛出携带确定原因的异常。 */
    public Map<String, Object> validateAgainstModel(Device device, CommandRequest request) {
        DeviceModel model = modelRegistry.modelFor(device);
        if (!model.supportedCommands().contains(request.command())) {
            throw new CommandRejectedException(RejectReason.UNKNOWN_COMMAND,
                    "设备模型 " + model.modelId() + " v" + model.version()
                            + " 不支持命令: " + request.command());
        }
        Map<String, Object> params = request.params() == null
                ? new HashMap<>() : new HashMap<>(request.params());
        for (String required : model.commandParams()
                .getOrDefault(request.command(), java.util.Set.of())) {
            if (!params.containsKey(required)) {
                throw new CommandRejectedException(RejectReason.MISSING_REQUIRED_FIELD,
                        "命令 " + request.command() + " 缺少必填参数: " + required);
            }
        }
        String targetAttr = model.commandTarget().get(request.command());
        if (targetAttr != null && !model.capabilities().contains(targetAttr)) {
            throw new CommandRejectedException(RejectReason.UNKNOWN_CAPABILITY,
                    "命令目标属性 " + targetAttr + " 不在模型能力集合内");
        }
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> domain =
                (Map<String, List<Object>>) (Map<?, ?>) model.commandValueDomain();
        if (domain != null && targetAttr != null) {
            List<Object> allowed = domain.get(request.command());
            Object value = params.get("value");
            if (allowed != null && value != null && allowed.stream()
                    .noneMatch(v -> String.valueOf(v).equals(String.valueOf(value)))) {
                throw new CommandRejectedException(RejectReason.INVALID_VALUE,
                        "参数 value=" + value + " 不在允许域 " + allowed + " 内");
            }
        }
        return params;
    }

    public CommandResult awaitResult(String commandId, long timeoutMillis) {
        Pending pending = pendings.get(commandId);
        if (pending == null) {
            return conclusions.get(commandId);
        }
        try {
            return pending.future().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return conclusions.get(commandId);
        } catch (ExecutionException | TimeoutException e) {
            return conclusions.get(commandId);
        }
    }

    public CommandResult getResult(String principalId, String commandId) {
        CommandResult result = conclusions.get(commandId);
        if (result == null) {
            return null;
        }
        if (principalId != null) {
            accessControl.requireDeviceAccess(principalId, result.deviceId());
        }
        return result;
    }

    public void activateDevice(Device device) {
        queue.start(device.deviceId(), request -> process(device, request));
    }

    private void process(Device device, CommandRequest request) {
        Pending pending = pendings.get(request.commandId());
        String principalId = pending == null ? null : pending.principalId();
        long timeout = request.deadlineAfterMillis() > 0
                ? request.deadlineAfterMillis() : properties.getCommandTimeoutMillis();

        StateSnapshot before = stateConvergence.snapshot(request.deviceId());
        if (before != null && !before.online()) {
            terminal(request, device, principalId, CommandOutcome.FAILED,
                    RejectReason.DEVICE_OFFLINE, "设备在命令排队期间离线，命令不执行", null);
            return;
        }

        // 驱动在独立线程执行，工作线程用 join 控制超时；NO_RESPONSE 时中断驱动线程
        final SimulatedDeviceDriver.DriverOutcome[] holder = new SimulatedDeviceDriver.DriverOutcome[1];
        Thread driverThread = new Thread(() ->
                holder[0] = driver.execute(device, request),
                "driver-" + request.commandId());
        driverThread.setDaemon(true);
        driverThread.start();
        try {
            driverThread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (driverThread.isAlive()) {
            driverThread.interrupt();
            terminal(request, device, principalId, CommandOutcome.TIMEOUT,
                    RejectReason.NO_RESPONSE,
                    "设备 " + timeout + "ms 内无响应，判定超时（已中断驱动调用）", null);
            return;
        }
        SimulatedDeviceDriver.DriverOutcome outcome = holder[0];
        if (outcome == null || !outcome.success()) {
            RejectReason fr = outcome == null ? RejectReason.EXECUTION_FAILED
                    : outcome.failureReason();
            CommandOutcome co = fr == RejectReason.NO_RESPONSE
                    ? CommandOutcome.TIMEOUT : CommandOutcome.FAILED;
            terminal(request, device, principalId, co, fr,
                    "驱动执行失败: " + fr, null);
            return;
        }

        StateSnapshot change = stateConvergence.confirmEffective(
                request.deviceId(), outcome.resultingAttributes());
        terminal(request, device, principalId, CommandOutcome.EFFECTIVE, null,
                "命令已生效并完成状态收敛", outcome.resultingAttributes());
        log.info("[命令-生效] commandId={} device={} 属性={}",
                request.commandId(), request.deviceId(), outcome.resultingAttributes());
        if (change != null && request.sourceRuleId() == null) {
            safePropagate(device, change);
        }
    }

    private void safePropagate(Device device, StateSnapshot change) {
        try {
            ruleEngineProvider.getObject().propagate(device, change);
        } catch (RuntimeException ex) {
            log.error("[命令-联动异常] device={}", device.deviceId(), ex);
        }
    }

    private CommandResult terminal(CommandRequest request, Device device, String principalId,
                                   CommandOutcome outcome, RejectReason reason,
                                   String detail, Map<String, Object> effectiveAttributes) {
        CommandResult result = new CommandResult(request.commandId(),
                request.deviceId(), outcome, reason, detail, null, null,
                effectiveAttributes == null ? Map.of() : effectiveAttributes,
                clock.nowMillis());
        conclusions.put(request.commandId(), result);
        Pending pending = pendings.remove(request.commandId());
        if (pending != null) {
            pending.future().complete(result);
        }

        // 回填被 MERGE 并入本命令的后继命令
        String mergedIncomingId = findMergedIncoming(request.deviceId(), request.commandId());
        if (mergedIncomingId != null) {
            CommandResult follower = new CommandResult(mergedIncomingId, request.deviceId(),
                    outcome, reason,
                    "被合并命令随主命令 " + request.commandId() + " 得到结论: " + outcome,
                    null, request.commandId(),
                    effectiveAttributes == null ? Map.of() : effectiveAttributes,
                    clock.nowMillis());
            conclusions.put(mergedIncomingId, follower);
            Pending fp = pendings.remove(mergedIncomingId);
            if (fp != null) {
                fp.future().complete(follower);
            }
            appendRecord(device, mergedIncomingId, request.sourceRuleId(),
                    fp == null ? null : fp.principalId(), outcome, reason, detail);
        }

        appendRecord(device, request.commandId(), request.sourceRuleId(), principalId,
                outcome, reason, detail);
        log.info("[命令-结论] commandId={} device={} outcome={} reason={} detail={}",
                request.commandId(), request.deviceId(), outcome, reason, detail);
        return result;
    }

    private String findMergedIncoming(String deviceId, String hostCommandId) {
        // 队列映射为 incomingId -> hostId；回填需反向查找：
        // 扫描结论表中仍处于 MERGED 受理态、并入本主命令的后继命令
        for (CommandResult r : conclusions.values()) {
            if (deviceId.equals(r.deviceId())
                    && hostCommandId.equals(r.mergedIntoCommandId())
                    && r.outcome() == CommandOutcome.ACCEPTED
                    && r.rejectReason() == RejectReason.MERGED) {
                return r.commandId();
            }
        }
        return null;
    }

    private void appendRecord(Device device, String commandId, String sourceRuleId,
                              String principalId, CommandOutcome outcome,
                              RejectReason reason, String detail) {
        List<String> chain = sourceRuleId == null
                ? List.of() : List.of("rule:" + sourceRuleId);
        executionLog.append(new ExecutionRecord(
                UUID.randomUUID().toString(), device.householdId(),
                device.deviceId(), commandId, sourceRuleId, principalId,
                outcome, reason, detail, chain, clock.nowMillis()));
    }

    private static final class CommandRejectedException extends RuntimeException {
        final RejectReason reason;

        CommandRejectedException(RejectReason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
