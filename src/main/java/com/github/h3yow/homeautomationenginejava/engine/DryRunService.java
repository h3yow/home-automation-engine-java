package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CandidateAction;
import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.ResolvedAction;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import com.github.h3yow.homeautomationenginejava.rule.Condition;
import com.github.h3yow.homeautomationenginejava.rule.CompositeCondition;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import com.github.h3yow.homeautomationenginejava.rule.TriggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 试运行 / 复盘沙箱。
 *
 * 在本地模拟世界中按“与真实执行同一套语义”推演一组状态变化片段：
 * - 片段收敛语义镜像 {@link StateConvergence}（未知能力/缺字段/乱序重复/离线不覆盖属性）；
 * - 规则评估、去重、成环检测镜像 {@link RuleEngine}（同一次状态变化同一规则不重复触发，
 *   只有首尾相接的真实触发链判定成环并终止该次传播，长链路继续推完）；
 * - 冲突裁决复用 {@link ConflictResolver#resolvePure}（确定且不依赖处理顺序）；
 * - 动作校验复用 {@link CommandService#validateAgainstModel} 与
 *   {@link AccessControl#requireRuleActionScope}（能力/版本/参数/越权结论与真实一致）；
 * - 动作判定生效后模拟世界单调推进，后续较旧/重复片段无法把预期状态改回旧值。
 *
 * 隔离性：全部推演状态为本方法内的局部对象，不下发真实命令、不占用设备队列、
 * 不改变真实设备状态、不写正式执行记录与成环记录；多份推演与真实流量可并发。
 * 确定性：判定时间固定为入参 evaluationTime；相同输入 + 相同环境指纹得到相同
 * dryRunId 与相同报告（内容哈希幂等回放）。
 */
@Service
public class DryRunService {

    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);

    /** 一个输入片段：假设的或历史的设备状态变化。 */
    public record Fragment(String deviceId, Long seq, Boolean online,
                           Map<String, Object> attributes) {
    }

    /** 推演规模上限（null 表示采用引擎默认配置；请求值只能收紧不能放宽）。 */
    public record Limits(Integer maxDepth, Integer maxEvents, Integer maxSteps) {
    }

    private final DeviceRegistry deviceRegistry;
    private final RuleRegistry ruleRegistry;
    private final ModelRegistry modelRegistry;
    private final StateConvergence stateConvergence;
    private final ConflictResolver conflictResolver;
    private final CommandService commandService;
    private final AccessControl accessControl;
    private final SimulatedDeviceDriver driver;
    private final EngineProperties properties;
    private final EngineClock clock;
    private final DryRunReportStore reportStore;

    public DryRunService(DeviceRegistry deviceRegistry, RuleRegistry ruleRegistry,
                         ModelRegistry modelRegistry, StateConvergence stateConvergence,
                         ConflictResolver conflictResolver, CommandService commandService,
                         AccessControl accessControl, SimulatedDeviceDriver driver,
                         EngineProperties properties, EngineClock clock,
                         DryRunReportStore reportStore) {
        this.deviceRegistry = deviceRegistry;
        this.ruleRegistry = ruleRegistry;
        this.modelRegistry = modelRegistry;
        this.stateConvergence = stateConvergence;
        this.conflictResolver = conflictResolver;
        this.commandService = commandService;
        this.accessControl = accessControl;
        this.driver = driver;
        this.properties = properties;
        this.clock = clock;
        this.reportStore = reportStore;
    }

    /**
     * 执行一次试运行推演。调用方需已完成家庭与设备鉴权。
     */
    public DryRunReport simulate(String householdId, long evaluationTime,
                                 List<Fragment> fragments, Limits limits) {
        int maxDepth = clampLimit(limits == null ? null : limits.maxDepth(),
                properties.getDryRunMaxDepth());
        int maxEvents = clampLimit(limits == null ? null : limits.maxEvents(),
                properties.getDryRunMaxEvents());
        int maxSteps = clampLimit(limits == null ? null : limits.maxSteps(),
                properties.getDryRunMaxSteps());

        List<Device> devices = deviceRegistry.listByHousehold(householdId);
        // 规则快照：整场推演使用同一份启用规则视图，保证可重复
        List<Rule> rules = ruleRegistry.allEnabled();
        SimWorld world = new SimWorld(devices, evaluationTime);

        String dryRunId = "dry-" + contentHash(householdId, evaluationTime,
                maxDepth, maxEvents, maxSteps, fragments, rules, devices);
        DryRunReport existing = reportStore.get(dryRunId);
        if (existing != null) {
            log.info("[试运行-幂等回放] id={} household={}", dryRunId, householdId);
            return existing;
        }

        log.info("[试运行-开始] id={} household={} 片段数={} 判定时间={} 上限=depth/{},events/{},steps/{}",
                dryRunId, householdId, fragments.size(), evaluationTime,
                maxDepth, maxEvents, maxSteps);

        Context ctx = new Context(evaluationTime, maxDepth, maxEvents, maxSteps, rules);
        List<DryRunReport.FragmentOutcome> fragmentOutcomes = new ArrayList<>();

        int index = 0;
        for (Fragment fragment : fragments) {
            boolean online = fragment.online() == null || fragment.online();
            Map<String, Object> attrs = fragment.attributes() == null
                    ? Map.of() : fragment.attributes();
            if (ctx.terminated) {
                fragmentOutcomes.add(new DryRunReport.FragmentOutcome(index,
                        fragment.deviceId(), false, "NOT_PROCESSED",
                        "推演已因 " + ctx.terminationReason + " 终止，片段未处理", -1));
                index++;
                continue;
            }
            Device device = deviceRegistry.find(fragment.deviceId());
            if (device == null) {
                fragmentOutcomes.add(new DryRunReport.FragmentOutcome(index,
                        fragment.deviceId(), false, "UNKNOWN_DEVICE",
                        "设备不存在 " + fragment.deviceId(), -1));
                ctx.step("FRAGMENT", "片段#" + index + " 设备不存在 device="
                        + fragment.deviceId() + "，拒绝");
                index++;
                continue;
            }
            if (!device.householdId().equals(householdId)) {
                fragmentOutcomes.add(new DryRunReport.FragmentOutcome(index,
                        fragment.deviceId(), false, "HOUSEHOLD_MISMATCH",
                        "片段设备 " + fragment.deviceId() + " 属于家庭 " + device.householdId()
                                + "，与本次推演家庭 " + householdId + " 不一致", -1));
                ctx.step("FRAGMENT", "片段#" + index + " 跨家庭设备 device="
                        + fragment.deviceId() + "，拒绝");
                index++;
                continue;
            }

            SimWorld.SimReport report = world.report(device, fragment.seq(), online, attrs);
            fragmentOutcomes.add(new DryRunReport.FragmentOutcome(index,
                    fragment.deviceId(), report.accepted(), report.rejectReason(),
                    report.detail(), report.appliedSeq()));
            ctx.step("FRAGMENT", "片段#" + index + " device=" + fragment.deviceId()
                    + " seq=" + fragment.seq() + " online=" + online + " attrs=" + attrs
                    + (report.accepted()
                            ? " => 采纳 assignedSeq=" + report.appliedSeq()
                            : " => 拒绝 " + report.rejectReason() + ": " + report.detail()));

            if (report.accepted() && report.changeView() != null
                    && !report.changeView().attributes().isEmpty()) {
                propagate(world, device, report.changeView(), ctx);
            }
            index++;
        }

        if (!ctx.terminated) {
            ctx.terminationReason = DryRunReport.TerminationReason.COMPLETED;
        }
        ctx.step("SUMMARY", "推演结束 终止原因=" + ctx.terminationReason
                + " 触发规则=" + ctx.firedRules + " 处理事件数=" + ctx.eventsProcessed);

        Map<String, DryRunReport.ExpectedState> expectedStates = new LinkedHashMap<>();
        for (Device device : devices) {
            StateSnapshot snap = world.snapshot(device.deviceId());
            if (snap == null) {
                expectedStates.put(device.deviceId(), new DryRunReport.ExpectedState(
                        device.deviceId(), true, Map.of()));
            } else {
                expectedStates.put(device.deviceId(), new DryRunReport.ExpectedState(
                        device.deviceId(), snap.online(), snap.attributes()));
            }
        }

        DryRunReport report = new DryRunReport(
                dryRunId, householdId, evaluationTime, clock.nowMillis(),
                ctx.terminationReason, ctx.stepsTruncated,
                List.copyOf(fragmentOutcomes), List.copyOf(ctx.steps),
                List.copyOf(ctx.firedRules), Map.copyOf(ctx.suppressed),
                List.copyOf(ctx.actions),
                ctx.cyclePath == null ? null : List.copyOf(ctx.cyclePath),
                Map.copyOf(expectedStates));
        log.info("[试运行-结束] id={} 终止={} 触发={} 抑制={} 成环={}",
                dryRunId, ctx.terminationReason, ctx.firedRules,
                ctx.suppressed.keySet(), ctx.cyclePath);
        return reportStore.putIfAbsent(report);
    }

    /**
     * 单次状态变化的传播推演，镜像 {@link RuleEngine#propagate} 的层级推进语义：
     * 先评估条件 -> 条件成立的回指规则才判成环（条件已不成立的回指不算环）
     * -> 本层统一裁决 -> 胜出动作模拟生效 -> 生效事件进入下一层；真实成环即终止本次传播。
     */
    private void propagate(SimWorld world, Device rootDevice, StateSnapshot rootChange,
                           Context ctx) {
        record Event(Device device, StateSnapshot snapshot, List<String> path) {
        }
        List<Event> currentLevel = new ArrayList<>();
        currentLevel.add(new Event(rootDevice, rootChange, List.of()));
        Set<String> globallyFired = new HashSet<>();

        ctx.step("EVAL", "根事件 device=" + rootDevice.deviceId()
                + " 变化属性=" + rootChange.attributes());

        int depth = 0;
        while (!currentLevel.isEmpty()) {
            depth++;
            if (depth > ctx.maxDepth) {
                ctx.terminate(DryRunReport.TerminationReason.MAX_DEPTH_EXCEEDED,
                        "触发链层数超过上限 " + ctx.maxDepth + "，推演截断");
                return;
            }
            List<Event> nextLevel = new ArrayList<>();

            // 1) 收集本层所有匹配规则
            Map<Rule, Event> matched = new LinkedHashMap<>();
            for (Event event : currentLevel) {
                if (ctx.eventsProcessed >= ctx.maxEvents) {
                    ctx.terminate(DryRunReport.TerminationReason.MAX_EVENTS_EXCEEDED,
                            "访问事件节点数达到上限 " + ctx.maxEvents + "，推演截断");
                    return;
                }
                ctx.eventsProcessed++;

                StateSnapshot full = world.snapshot(event.device().deviceId());
                StateSnapshot base = full == null ? event.snapshot() : full;
                TriggerContext triggerCtx = new TriggerContext(
                        event.device().deviceId(), event.device().householdId(),
                        base, event.snapshot().attributes(), ctx.evaluationTime);

                for (Rule rule : ctx.rules) {
                    if (!rule.householdId().equals(event.device().householdId())) {
                        continue;
                    }
                    if (matched.containsKey(rule)) {
                        continue;
                    }
                    if (!ruleTouchesDevice(rule, event.device().deviceId())) {
                        continue;
                    }
                    boolean satisfied;
                    try {
                        satisfied = rule.condition().evaluate(triggerCtx);
                    } catch (RuntimeException ex) {
                        ctx.step("EVAL", "规则 " + rule.ruleId()
                                + " 条件评估异常: " + ex.getMessage());
                        continue;
                    }
                    ctx.step("EVAL", "评估规则 " + rule.ruleId() + " [" + rule.name()
                            + "] priority=" + rule.priority() + " 触发设备="
                            + event.device().deviceId() + " 条件["
                            + rule.condition().describe() + "]=" + satisfied
                            + (satisfied ? " => 命中" : " => 不满足，不触发"));
                    if (!satisfied) {
                        // 条件已不再成立的回指规则不会被再次触发，不构成成环，链路继续推完
                        continue;
                    }
                    // 条件确实成立且规则已在当前触发路径上：首尾相接的真实闭环
                    if (event.path().contains(rule.ruleId())) {
                        List<String> cyclePath = new ArrayList<>(event.path());
                        cyclePath.add(rule.ruleId());
                        if (ctx.cyclePath == null) {
                            ctx.cyclePath = cyclePath;
                        }
                        ctx.step("CYCLE", "条件成立且规则回指当前路径: " + cyclePath
                                + "，判定真实成环并终止本次传播（首尾相接）");
                        log.warn("[试运行-成环] 环={}", cyclePath);
                        return;
                    }
                    // 已在本传播其他分支/层级触发过：去重跳过，不算成环
                    if (globallyFired.contains(rule.ruleId())) {
                        ctx.step("EVAL", "规则 " + rule.ruleId()
                                + " 本次传播已触发过，去重跳过");
                        continue;
                    }
                    matched.put(rule, event);
                }
            }

            if (matched.isEmpty()) {
                ctx.step("EVAL", "第 " + depth + " 层无命中规则，传播收敛结束");
                break;
            }

            // 2) 候选动作
            List<CandidateAction> candidates = new ArrayList<>();
            for (Map.Entry<Rule, Event> e : matched.entrySet()) {
                Rule rule = e.getKey();
                for (RuleAction action : rule.actions()) {
                    candidates.add(new CandidateAction(rule.ruleId(), rule.householdId(),
                            action.deviceId(), action.command(), action.params(),
                            rule.priority()));
                }
            }

            // 3) 冲突裁决（纯函数，与真实执行同一逻辑，不污染共享抑制记录）
            List<ResolvedAction> resolved = conflictResolver.resolvePure(candidates);
            for (ResolvedAction ra : resolved) {
                ra.suppressedWithReason().forEach((ruleId, sup) -> {
                    ctx.suppressed.put(ruleId, sup.reason());
                    ctx.step("CONFLICT", "动作被抑制 rule=" + ruleId + " winner="
                            + sup.winnerRuleId() + " 原因=" + sup.reason());
                });
            }

            // 4) 胜出动作在模拟世界“生效”，产生下一层事件
            for (ResolvedAction ra : resolved) {
                CandidateAction winner = ra.winner();
                Rule rule = matched.keySet().stream()
                        .filter(r -> r.ruleId().equals(winner.ruleId()))
                        .findFirst().orElseThrow();
                globallyFired.add(winner.ruleId());
                ctx.firedRules.add(winner.ruleId());
                Event sourceEvent = matched.get(rule);

                Device target = deviceRegistry.find(winner.deviceId());
                if (target == null) {
                    ctx.step("ACTION", "动作目标设备不存在 rule=" + winner.ruleId()
                            + " device=" + winner.deviceId() + "，跳过");
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "REJECTED",
                            "UNKNOWN_DEVICE", Map.of()));
                    continue;
                }

                try {
                    accessControl.requireRuleActionScope(winner.ruleId(), winner.deviceId());
                } catch (AccessDeniedException ex) {
                    ctx.suppressed.put(winner.ruleId(),
                            "SCOPE_DENIED(" + ex.reason() + "): " + ex.getMessage());
                    ctx.step("ACTION", "动作越界被拒绝 rule=" + winner.ruleId()
                            + " 原因=" + ex.reason() + " " + ex.getMessage());
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "REJECTED",
                            "SCOPE_DENIED", Map.of()));
                    continue;
                }

                DeviceModel model;
                try {
                    model = modelRegistry.modelFor(target);
                } catch (IllegalArgumentException ex) {
                    ctx.step("ACTION", "动作目标模型不支持 rule=" + winner.ruleId()
                            + " " + ex.getMessage());
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "REJECTED",
                            "UNSUPPORTED_MODEL_VERSION", Map.of()));
                    continue;
                }

                CommandRequest request = new CommandRequest(
                        "dry-cmd", winner.deviceId(), winner.householdId(),
                        winner.command(), winner.params(), "dry", winner.ruleId(), 0L);
                Map<String, Object> params;
                try {
                    params = commandService.validateAgainstModel(target, request);
                } catch (CommandService.CommandRejectedException ex) {
                    ctx.step("ACTION", "动作校验拒绝 rule=" + winner.ruleId()
                            + " 原因=" + ex.reason + " " + ex.getMessage());
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "REJECTED",
                            ex.reason.name(), Map.of()));
                    continue;
                }

                StateSnapshot simSnap = world.snapshot(winner.deviceId());
                SimulatedDeviceDriver.FaultMode fault = driver.fault(winner.deviceId());
                if ((simSnap != null && !simSnap.online())
                        || fault == SimulatedDeviceDriver.FaultMode.OFFLINE) {
                    ctx.step("ACTION", "动作不会生效 rule=" + winner.ruleId()
                            + " device=" + winner.deviceId()
                            + " 原因=DEVICE_OFFLINE（设备当前不可用，明确不生效）");
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "FAILED",
                            "DEVICE_OFFLINE", Map.of()));
                    continue;
                }
                if (fault == SimulatedDeviceDriver.FaultMode.FAIL_ALWAYS) {
                    ctx.step("ACTION", "动作不会生效 rule=" + winner.ruleId()
                            + " 原因=EXECUTION_FAILED（设备故障注入）");
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "FAILED",
                            "EXECUTION_FAILED", Map.of()));
                    continue;
                }
                if (fault == SimulatedDeviceDriver.FaultMode.NO_RESPONSE) {
                    ctx.step("ACTION", "动作不会生效 rule=" + winner.ruleId()
                            + " 原因=NO_RESPONSE（设备无响应，判定超时）");
                    ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                            winner.deviceId(), winner.command(), "TIMEOUT",
                            "NO_RESPONSE", Map.of()));
                    continue;
                }

                // 与驱动相同的结果属性推导：目标属性 + (参数value 或 模型常量结果)
                String targetAttr = model.commandTarget().get(winner.command());
                Object value = params.get("value");
                if (value == null) {
                    value = model.commandResult().get(winner.command());
                }
                Map<String, Object> resulting =
                        (targetAttr == null || value == null) ? Map.of()
                                : Map.of(targetAttr, value);
                world.confirmEffective(winner.deviceId(), resulting);
                ctx.step("ACTION", "动作将生效 rule=" + winner.ruleId() + " device="
                        + winner.deviceId() + " command=" + winner.command()
                        + " 结果属性=" + resulting + "（模拟生效，未真实下发）");
                ctx.actions.add(new DryRunReport.ActionOutcome(winner.ruleId(),
                        winner.deviceId(), winner.command(), "EFFECTIVE",
                        null, resulting));

                StateSnapshot after = world.snapshot(winner.deviceId());
                if (after != null && targetAttr != null
                        && after.attributes().containsKey(targetAttr)) {
                    StateSnapshot changeEvent = new StateSnapshot(winner.deviceId(),
                            after.seq(), true,
                            Map.of(targetAttr, after.attributes().get(targetAttr)),
                            ctx.evaluationTime);
                    List<String> newPath = new ArrayList<>(sourceEvent.path());
                    newPath.add(winner.ruleId());
                    nextLevel.add(new Event(target, changeEvent, newPath));
                }
            }

            currentLevel = nextLevel;
        }
    }

    /** 与 RuleEngine 相同的“规则条件是否绑定到指定设备”判定。 */
    private boolean ruleTouchesDevice(Rule rule, String deviceId) {
        return touches(rule.condition(), deviceId);
    }

    private boolean touches(Condition condition, String deviceId) {
        if (condition instanceof StateCondition sc) {
            return sc.deviceId() == null || sc.deviceId().equals(deviceId);
        }
        if (condition instanceof CompositeCondition cc) {
            return cc.children().stream().anyMatch(child -> touches(child, deviceId));
        }
        return true;
    }

    private static int clampLimit(Integer requested, int configured) {
        if (requested == null) {
            return configured;
        }
        return Math.max(1, Math.min(requested, configured));
    }

    /**
     * 内容哈希：输入片段 + 判定时间 + 生效上限 + 启用规则指纹 + 设备基线指纹。
     * 相同输入与相同环境得到相同 dryRunId，重复推演幂等回放同一报告。
     */
    private String contentHash(String householdId, long evaluationTime,
                               int maxDepth, int maxEvents, int maxSteps,
                               List<Fragment> fragments, List<Rule> rules,
                               List<Device> devices) {
        StringBuilder sb = new StringBuilder();
        sb.append(householdId).append('\n').append(evaluationTime).append('\n')
                .append(maxDepth).append('|').append(maxEvents).append('|')
                .append(maxSteps).append('\n');
        for (Fragment f : fragments) {
            boolean online = f.online() == null || f.online();
            sb.append("F|").append(f.deviceId()).append(';').append(f.seq())
                    .append(';').append(online).append(';')
                    .append(canonicalAttrs(f.attributes())).append('\n');
        }
        for (Rule rule : rules) {
            if (!rule.householdId().equals(householdId)) {
                continue;
            }
            sb.append("R|").append(rule.ruleId()).append('|').append(rule.priority())
                    .append('|').append(rule.condition().describe()).append('|');
            for (RuleAction action : rule.actions()) {
                sb.append(action.deviceId()).append(':').append(action.command())
                        .append(':').append(canonicalAttrs(action.params())).append(',');
            }
            sb.append('\n');
        }
        for (Device device : devices) {
            StateSnapshot snap = stateConvergence.snapshot(device.deviceId());
            sb.append("D|").append(device.deviceId()).append('|')
                    .append(device.modelId()).append('|').append(device.modelVersion())
                    .append('|').append(driver.fault(device.deviceId())).append('|');
            if (snap == null) {
                sb.append("NO_SNAPSHOT");
            } else {
                sb.append(snap.seq()).append(';').append(snap.online()).append(';')
                        .append(canonicalAttrs(snap.attributes()));
            }
            sb.append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String canonicalAttrs(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sorted = new TreeMap<>(attrs);
        StringBuilder sb = new StringBuilder("{");
        sorted.forEach((k, v) -> sb.append(k).append('=')
                .append(String.valueOf(v)).append(','));
        sb.append('}');
        return sb.toString();
    }

    /** 单次推演的可变上下文（方法局部，天然与真实运行及其他推演隔离）。 */
    private static final class Context {
        final long evaluationTime;
        final int maxDepth;
        final int maxEvents;
        final int maxSteps;
        final List<Rule> rules;
        final List<DryRunReport.StepEntry> steps = new ArrayList<>();
        final Set<String> firedRules = new java.util.LinkedHashSet<>();
        final Map<String, String> suppressed = new LinkedHashMap<>();
        final List<DryRunReport.ActionOutcome> actions = new ArrayList<>();
        List<String> cyclePath;
        int eventsProcessed;
        boolean stepsTruncated;
        boolean terminated;
        DryRunReport.TerminationReason terminationReason =
                DryRunReport.TerminationReason.COMPLETED;

        Context(long evaluationTime, int maxDepth, int maxEvents, int maxSteps,
                List<Rule> rules) {
            this.evaluationTime = evaluationTime;
            this.maxDepth = maxDepth;
            this.maxEvents = maxEvents;
            this.maxSteps = maxSteps;
            this.rules = rules;
        }

        void step(String phase, String message) {
            if (steps.size() >= maxSteps) {
                stepsTruncated = true;
                return;
            }
            steps.add(new DryRunReport.StepEntry(steps.size() + 1, phase, message));
        }

        void terminate(DryRunReport.TerminationReason reason, String detail) {
            this.terminated = true;
            this.terminationReason = reason;
            step("LIMIT", detail);
            log.warn("[试运行-截断] {} {}", reason, detail);
        }
    }

    /**
     * 模拟状态世界：语义逐行镜像 {@link StateConvergence}（含 seq 单调、
     * 未知能力拒绝、必填字段、离线不覆盖属性、生效确认单调推进），
     * 但全部状态为单次推演的局部副本，绝不触碰真实收敛器。
     */
    private final class SimWorld {

        private record Entry(StateSnapshot snapshot, long assignedSeq, long reportedSeq) {
        }

        /** 片段收敛结论（字段语义对齐 ReportResult，附变化视图）。 */
        record SimReport(boolean accepted, String rejectReason, String detail,
                         long appliedSeq, StateSnapshot changeView) {
        }

        private final Map<String, Entry> store = new HashMap<>();
        private final long evalTime;

        SimWorld(List<Device> devices, long evalTime) {
            this.evalTime = evalTime;
            for (Device device : devices) {
                StateSnapshot snap = stateConvergence.snapshot(device.deviceId());
                if (snap != null) {
                    store.put(device.deviceId(), new Entry(snap, snap.seq(), snap.seq()));
                }
            }
        }

        StateSnapshot snapshot(String deviceId) {
            Entry e = store.get(deviceId);
            return e == null ? null : e.snapshot();
        }

        SimReport report(Device device, Long seq, boolean online,
                         Map<String, Object> attrs) {
            DeviceModel model;
            try {
                model = modelRegistry.modelFor(device);
            } catch (IllegalArgumentException ex) {
                return new SimReport(false, "UNSUPPORTED_MODEL_VERSION",
                        ex.getMessage(), -1, null);
            }
            Entry current = store.get(device.deviceId());

            List<String> unknown = attrs.keySet().stream()
                    .filter(k -> !model.capabilities().contains(k))
                    .sorted().toList();
            if (!unknown.isEmpty()) {
                return new SimReport(false, "UNKNOWN_CAPABILITY",
                        "上报包含模型 " + model.modelId() + " v" + model.version()
                                + " 未知能力属性: " + unknown,
                        current == null ? -1 : current.snapshot().seq(), null);
            }

            List<String> missing = attrs.isEmpty() ? List.of()
                    : model.requiredReportFields().stream()
                    .filter(f -> !attrs.containsKey(f))
                    .sorted().toList();
            if (!missing.isEmpty()) {
                return new SimReport(false, "MISSING_REQUIRED_FIELD",
                        "上报缺少必填字段: " + missing,
                        current == null ? -1 : current.snapshot().seq(), null);
            }

            long assigned = (current == null ? 0 : current.assignedSeq()) + 1;
            long reportedSeq = seq == null ? assigned : seq;
            if (seq != null && current != null && reportedSeq <= current.reportedSeq()) {
                return new SimReport(false, "OUTDATED_SEQUENCE",
                        "上报序号 " + reportedSeq + " 不大于已收敛序号 "
                                + current.reportedSeq() + "，判定为乱序/重复，状态不回退",
                        current.snapshot().seq(), null);
            }

            boolean wasOnline = current == null || current.snapshot().online();
            Map<String, Object> baseAttrs = current == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(current.snapshot().attributes());
            Map<String, Object> changed = new LinkedHashMap<>();
            if (online) {
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    Object old = baseAttrs.get(e.getKey());
                    if (!valueEquals(old, e.getValue())) {
                        changed.put(e.getKey(), e.getValue());
                    }
                    baseAttrs.put(e.getKey(), e.getValue());
                }
            }
            Map<String, Object> finalAttrs = Map.copyOf(baseAttrs);
            StateSnapshot snapshot = new StateSnapshot(device.deviceId(), assigned,
                    online, finalAttrs, evalTime);
            store.put(device.deviceId(), new Entry(snapshot, assigned, reportedSeq));
            StateSnapshot changeView = (!changed.isEmpty() || wasOnline != online)
                    ? new StateSnapshot(device.deviceId(), assigned, online,
                            Map.copyOf(changed), evalTime)
                    : null;
            return new SimReport(true, null, null, assigned, changeView);
        }

        /** 镜像 confirmEffective：合并期望属性并单调推进序号；无变化返回 null。 */
        StateSnapshot confirmEffective(String deviceId, Map<String, Object> expected) {
            Entry current = store.get(deviceId);
            if (current == null) {
                StateSnapshot bootstrap = new StateSnapshot(deviceId, 0, true,
                        Map.of(), evalTime);
                current = new Entry(bootstrap, 0, 0);
                store.put(deviceId, current);
            }
            Map<String, Object> merged =
                    new LinkedHashMap<>(current.snapshot().attributes());
            Map<String, Object> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                Object old = merged.get(e.getKey());
                if (!valueEquals(old, e.getValue())) {
                    changed.put(e.getKey(), e.getValue());
                }
                merged.put(e.getKey(), e.getValue());
            }
            if (changed.isEmpty()) {
                return null;
            }
            long assigned = current.assignedSeq() + 1;
            StateSnapshot snapshot = new StateSnapshot(deviceId, assigned, true,
                    Map.copyOf(merged), evalTime);
            store.put(deviceId, new Entry(snapshot, assigned, assigned));
            return new StateSnapshot(deviceId, assigned, true, Map.copyOf(changed),
                    evalTime);
        }

        private boolean valueEquals(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) {
                return x.doubleValue() == y.doubleValue();
            }
            return Objects.equals(a, b);
        }
    }
}
