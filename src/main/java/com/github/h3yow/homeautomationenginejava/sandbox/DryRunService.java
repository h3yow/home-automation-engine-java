package com.github.h3yow.homeautomationenginejava.sandbox;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.CommandService;
import com.github.h3yow.homeautomationenginejava.engine.ConflictResolver;
import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.EngineClock;
import com.github.h3yow.homeautomationenginejava.engine.EngineProperties;
import com.github.h3yow.homeautomationenginejava.engine.ModelRegistry;
import com.github.h3yow.homeautomationenginejava.engine.RuleRegistry;
import com.github.h3yow.homeautomationenginejava.engine.StateConvergence;
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
 * 试运行/复盘沙箱（与真实运行完全隔离）。
 *
 * 语义与真实执行一致（复用同一套组件与判定逻辑）：
 * - 片段收敛：与 StateConvergence 相同的未知能力/必填字段/单调序号/离线语义；
 * - 规则传播：与 RuleEngine 相同的 BFS 分层、一次传播去重、路径成环检测；
 * - 冲突裁决：复用 ConflictResolver 纯函数式 resolve（不触碰共享抑制记录）；
 * - 动作校验：复用 AccessControl 越界语义与 CommandService 模型/参数校验；
 * - 离线设备：给出“不会生效”的明确结论，不假装成功。
 *
 * 隔离性：全部可变状态（设备预期状态、触发集合、报告）为单次调用私有；
 * 对引擎共享组件只读，不下发命令、不占队列、不改真实状态、不写正式记录。
 * 因此多份试运行可与真实命令/上报并发，彼此结果互不影响。
 *
 * 确定性：判定时间点固定为请求指定值；规则/设备遍历顺序固定；
 * 相同输入（含相同判定时间点）重复推演得到相同报告。
 */
@Service
public class DryRunService {

    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);

    private final AccessControl accessControl;
    private final DeviceRegistry deviceRegistry;
    private final RuleRegistry ruleRegistry;
    private final ModelRegistry modelRegistry;
    private final StateConvergence stateConvergence;
    private final ConflictResolver conflictResolver;
    private final CommandService commandService;
    private final EngineProperties properties;
    private final EngineClock clock;

    public DryRunService(AccessControl accessControl, DeviceRegistry deviceRegistry,
                         RuleRegistry ruleRegistry, ModelRegistry modelRegistry,
                         StateConvergence stateConvergence,
                         ConflictResolver conflictResolver, CommandService commandService,
                         EngineProperties properties, EngineClock clock) {
        this.accessControl = accessControl;
        this.deviceRegistry = deviceRegistry;
        this.ruleRegistry = ruleRegistry;
        this.modelRegistry = modelRegistry;
        this.stateConvergence = stateConvergence;
        this.conflictResolver = conflictResolver;
        this.commandService = commandService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 沙箱内单台设备的预期状态（单调序号语义与真实收敛器一致）。 */
    private static final class SandboxEntry {
        Map<String, Object> attributes;
        long assignedSeq;
        long reportedSeq;
        boolean online;

        SandboxEntry(Map<String, Object> attributes, long assignedSeq,
                     long reportedSeq, boolean online) {
            this.attributes = attributes;
            this.assignedSeq = assignedSeq;
            this.reportedSeq = reportedSeq;
            this.online = online;
        }
    }

    /** 传播事件：设备 + 本次变化属性 + 规则触发路径（用于成环检测）。 */
    private record Event(Device device, Map<String, Object> changedAttributes,
                         List<String> path) {
    }

    /** 单次推演的私有会话：全部可变状态都挂在这里，调用结束即丢弃。 */
    private static final class Session {
        final String householdId;
        final long evalTime;
        final int maxDepth;
        final int maxVisitedNodes;
        final int maxReportEntries;
        final Map<String, SandboxEntry> state = new HashMap<>();
        final List<DryRunReport.FragmentOutcome> fragmentOutcomes = new ArrayList<>();
        final List<String> firedRules = new ArrayList<>();
        final Map<String, String> suppressed = new LinkedHashMap<>();
        final List<List<String>> cyclePaths = new ArrayList<>();
        final List<String> steps = new ArrayList<>();
        boolean stepsTruncated;
        String termination = DryRunReport.Termination.COMPLETED.name();
        int visitedNodes;

        Session(String householdId, long evalTime, int maxDepth,
                int maxVisitedNodes, int maxReportEntries) {
            this.householdId = householdId;
            this.evalTime = evalTime;
            this.maxDepth = maxDepth;
            this.maxVisitedNodes = maxVisitedNodes;
            this.maxReportEntries = maxReportEntries;
        }

        void step(String message) {
            if (steps.size() < maxReportEntries) {
                steps.add(message);
            } else {
                stepsTruncated = true;
            }
        }

        /** 访问节点计数；返回 false 表示已超出节点预算，必须终止推演。 */
        boolean budget() {
            visitedNodes++;
            if (visitedNodes > maxVisitedNodes) {
                termination = DryRunReport.Termination.NODE_LIMIT_REACHED.name();
                step("访问节点数达到上限 " + maxVisitedNodes + "，推演终止（部分结果已保留）");
                return false;
            }
            return true;
        }

        boolean terminated() {
            return !DryRunReport.Termination.COMPLETED.name().equals(termination);
        }
    }

    public DryRunReport dryRun(String principalId, DryRunRequest request) {
        Objects.requireNonNull(request, "request");
        String householdId = request.householdId();
        if (householdId == null || householdId.isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST: householdId 不能为空");
        }
        accessControl.requireHouseholdScope(principalId, householdId);

        long evalTime = request.evaluationTimeMillis() == null
                ? clock.nowMillis() : request.evaluationTimeMillis();
        DryRunRequest.Limits limits = request.limits();
        int maxDepth = cap(limits == null ? null : limits.maxDepth(),
                properties.getDryRunMaxDepth());
        int maxNodes = cap(limits == null ? null : limits.maxVisitedNodes(),
                properties.getDryRunMaxVisitedNodes());
        int maxEntries = cap(limits == null ? null : limits.maxReportEntries(),
                properties.getDryRunMaxReportEntries());

        Session session = new Session(householdId, evalTime, maxDepth, maxNodes, maxEntries);

        // 1) 建立基线：复制本家庭设备的真实快照（只读，绝不回写）
        List<Device> devices = deviceRegistry.listByHousehold(householdId);
        for (Device device : devices) {
            StateSnapshot snapshot = stateConvergence.snapshot(device.deviceId());
            if (snapshot == null) {
                session.state.put(device.deviceId(),
                        new SandboxEntry(new LinkedHashMap<>(), 0, 0, true));
            } else {
                session.state.put(device.deviceId(),
                        new SandboxEntry(new LinkedHashMap<>(snapshot.attributes()),
                                snapshot.seq(), snapshot.seq(), snapshot.online()));
            }
        }
        session.step("推演开始 household=" + householdId + " 判定时间=" + evalTime
                + " 片段数=" + (request.fragments() == null ? 0 : request.fragments().size())
                + " 上限[depth=" + maxDepth + ", nodes=" + maxNodes
                + ", entries=" + maxEntries + "]");
        log.info("[试运行-开始] household={} evalTime={} fragments={}",
                householdId, evalTime,
                request.fragments() == null ? 0 : request.fragments().size());

        // 2) 按输入顺序逐条应用片段；每条被采纳且产生属性变化的片段触发一次传播
        List<DryRunRequest.StateFragment> fragments = request.fragments() == null
                ? List.of() : request.fragments();
        for (int i = 0; i < fragments.size() && !session.terminated(); i++) {
            if (!session.budget()) {
                break;
            }
            applyFragment(session, i, fragments.get(i));
        }

        // 3) 汇总预期状态（按设备 id 排序，保证输出确定）
        Map<String, DryRunReport.ExpectedState> expected = new LinkedHashMap<>();
        for (Device device : devices) {
            SandboxEntry entry = session.state.get(device.deviceId());
            expected.put(device.deviceId(), new DryRunReport.ExpectedState(
                    device.deviceId(), entry.online,
                    Map.copyOf(new TreeMap<>(entry.attributes))));
        }
        session.step("推演结束 termination=" + session.termination
                + " 触发规则=" + session.firedRules);

        String reportId = reportId(request, evalTime, maxDepth, maxNodes, maxEntries,
                session, devices);
        log.info("[试运行-结束] household={} termination={} fired={} cycles={}",
                householdId, session.termination, session.firedRules, session.cyclePaths);
        return new DryRunReport(reportId, householdId, evalTime,
                new DryRunReport.LimitsApplied(maxDepth, maxNodes, maxEntries),
                List.copyOf(session.fragmentOutcomes),
                List.copyOf(session.firedRules),
                Map.copyOf(session.suppressed),
                session.cyclePaths.stream().map(List::copyOf).toList(),
                expected,
                List.copyOf(session.steps),
                session.stepsTruncated,
                session.termination);
    }

    private static int cap(Integer requested, int configured) {
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }

    /**
     * 应用一条状态片段：与真实收敛器相同的校验与单调序号语义。
     * 被采纳且产生属性变化时，立即在沙箱内做完整规则传播——
     * 因此动作生效会推进设备序号，后续较旧/重复片段无法把预期状态改回旧值。
     */
    private void applyFragment(Session session, int index,
                               DryRunRequest.StateFragment fragment) {
        String deviceId = fragment.deviceId();
        Device device = deviceId == null ? null : deviceRegistry.find(deviceId);
        if (device == null) {
            rejectFragment(session, index, deviceId, "UNKNOWN_DEVICE",
                    "片段目标设备不存在: " + deviceId);
            return;
        }
        if (!device.householdId().equals(session.householdId)) {
            rejectFragment(session, index, deviceId, "HOUSEHOLD_MISMATCH",
                    "片段设备 " + deviceId + " 属于家庭 " + device.householdId()
                            + "，与推演家庭 " + session.householdId + " 不一致，拒绝采纳");
            return;
        }
        DeviceModel model;
        try {
            model = modelRegistry.modelFor(device);
        } catch (IllegalArgumentException ex) {
            rejectFragment(session, index, deviceId, "UNSUPPORTED_MODEL_VERSION",
                    ex.getMessage());
            return;
        }
        Map<String, Object> attrs = fragment.attributes() == null
                ? Map.of() : fragment.attributes();
        List<String> unknown = attrs.keySet().stream()
                .filter(k -> !model.capabilities().contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            rejectFragment(session, index, deviceId, "UNKNOWN_CAPABILITY",
                    "片段包含模型 " + model.modelId() + " v" + model.version()
                            + " 未知能力属性: " + unknown);
            return;
        }
        List<String> missing = attrs.isEmpty() ? List.of()
                : model.requiredReportFields().stream()
                .filter(f -> !attrs.containsKey(f)).sorted().toList();
        if (!missing.isEmpty()) {
            rejectFragment(session, index, deviceId, "MISSING_REQUIRED_FIELD",
                    "片段缺少必填字段: " + missing);
            return;
        }

        SandboxEntry entry = session.state.get(deviceId);
        boolean online = fragment.online() == null || fragment.online();
        long reportedSeq = fragment.seq() == null
                ? entry.assignedSeq + 1 : fragment.seq();
        if (fragment.seq() != null && reportedSeq <= entry.reportedSeq) {
            rejectFragment(session, index, deviceId, "OUTDATED_SEQUENCE",
                    "片段序号 " + reportedSeq + " 不大于已收敛序号 " + entry.reportedSeq
                            + "，判定为乱序/重复，预期状态不回退");
            return;
        }

        boolean wasOnline = entry.online;
        Map<String, Object> changed = new LinkedHashMap<>();
        if (online) {
            for (Map.Entry<String, Object> e : attrs.entrySet()) {
                Object old = entry.attributes.get(e.getKey());
                if (!valueEquals(old, e.getValue())) {
                    changed.put(e.getKey(), e.getValue());
                }
                entry.attributes.put(e.getKey(), e.getValue());
            }
        }
        entry.assignedSeq = entry.assignedSeq + 1;
        entry.reportedSeq = reportedSeq;
        entry.online = online;

        session.fragmentOutcomes.add(new DryRunReport.FragmentOutcome(
                index, deviceId, true, null,
                "片段已采纳 assignedSeq=" + entry.assignedSeq
                        + (online ? "" : "（离线片段仅翻转在线位，不覆盖能力属性）")));
        session.step("片段#" + index + " 采纳 device=" + deviceId + " online=" + online
                + " 变化属性=" + changed);

        // 与真实 EngineWiring 一致：仅属性变化触发规则；纯在线位翻转不评估
        if (!changed.isEmpty()) {
            propagate(session, device, changed);
        } else if (wasOnline != online) {
            session.step("片段#" + index + " 仅在线位翻转，不触发规则评估");
        }
    }

    private void rejectFragment(Session session, int index, String deviceId,
                                String reason, String detail) {
        session.fragmentOutcomes.add(new DryRunReport.FragmentOutcome(
                index, deviceId, false, reason, detail));
        session.step("片段#" + index + " 拒绝 device=" + deviceId
                + " 原因=" + reason + " " + detail);
        log.info("[试运行-片段拒绝] device={} 原因={} {}", deviceId, reason, detail);
    }

    /**
     * 沙箱内规则传播：与 RuleEngine 相同的 BFS 分层、一次传播去重、
     * 路径成环检测（检测到环立即终止本次传播）；动作不下发真实命令，
     * 按与驱动一致的规则计算生效属性并推进沙箱状态。
     */
    private void propagate(Session session, Device rootDevice,
                           Map<String, Object> rootChanged) {
        Set<String> globallyFired = new HashSet<>();
        List<Event> currentLevel = new ArrayList<>();
        currentLevel.add(new Event(rootDevice, rootChanged, List.of()));
        session.step("传播开始 device=" + rootDevice.deviceId() + " 变化属性=" + rootChanged);

        int depth = 0;
        while (!currentLevel.isEmpty()) {
            depth++;
            if (depth > session.maxDepth) {
                session.termination = DryRunReport.Termination.DEPTH_LIMIT_REACHED.name();
                session.step("链路层数达到上限 " + session.maxDepth
                        + "，推演终止（部分结果已保留）");
                return;
            }
            List<Event> nextLevel = new ArrayList<>();

            // 1) 收集本层全部匹配规则（候选），统一裁决
            Map<Rule, Event> matched = new LinkedHashMap<>();
            boolean cycle = false;
            for (Event event : currentLevel) {
                if (!session.budget()) {
                    return;
                }
                SandboxEntry entry = session.state.get(event.device().deviceId());
                StateSnapshot base = new StateSnapshot(event.device().deviceId(),
                        entry.assignedSeq, entry.online,
                        Map.copyOf(entry.attributes), session.evalTime);
                TriggerContext ctx = new TriggerContext(event.device().deviceId(),
                        event.device().householdId(), base,
                        event.changedAttributes(), session.evalTime);

                for (Rule rule : ruleRegistry.allEnabled()) {
                    if (!session.budget()) {
                        return;
                    }
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
                        satisfied = rule.condition().evaluate(ctx);
                    } catch (RuntimeException ex) {
                        session.step("规则 " + rule.ruleId() + " 条件评估异常: "
                                + ex.getMessage());
                        continue;
                    }
                    session.step("评估规则 " + rule.ruleId() + " [" + rule.name()
                            + "] priority=" + rule.priority() + " 触发设备="
                            + event.device().deviceId() + " 条件["
                            + rule.condition().describe() + "]=" + satisfied
                            + (satisfied ? " => 命中" : " => 不满足，不触发"));
                    if (!satisfied) {
                        continue;
                    }
                    // 环检测优先于去重：条件确实再次满足且规则已在当前路径上，
                    // 才是首尾相接的真实触发链闭环（与真实执行同一判定顺序）
                    if (event.path().contains(rule.ruleId())) {
                        List<String> cyclePath = new ArrayList<>(event.path());
                        cyclePath.add(rule.ruleId());
                        session.cyclePaths.add(List.copyOf(cyclePath));
                        session.step("检测到规则环: " + cyclePath + "，本次传播终止");
                        log.warn("[试运行-成环] 环={}", cyclePath);
                        cycle = true;
                        break;
                    }
                    if (globallyFired.contains(rule.ruleId())) {
                        session.step("规则 " + rule.ruleId() + " 本次传播已触发过，去重跳过");
                        continue;
                    }
                    matched.put(rule, event);
                }
                if (cycle) {
                    break;
                }
            }
            if (cycle) {
                return;
            }
            if (matched.isEmpty()) {
                session.step("第 " + depth + " 层无命中规则，传播收敛结束");
                break;
            }

            // 2) 候选动作 + 确定性冲突裁决（纯函数，不触碰共享抑制记录）
            List<CandidateAction> candidates = new ArrayList<>();
            for (Map.Entry<Rule, Event> e : matched.entrySet()) {
                Rule rule = e.getKey();
                for (RuleAction action : rule.actions()) {
                    candidates.add(new CandidateAction(rule.ruleId(), rule.householdId(),
                            action.deviceId(), action.command(), action.params(),
                            rule.priority()));
                }
            }
            List<ResolvedAction> resolved = conflictResolver.resolve(candidates);
            for (ResolvedAction ra : resolved) {
                ra.suppressedWithReason().forEach((ruleId, sup) -> {
                    session.suppressed.put(ruleId, sup.reason());
                    session.step("动作被抑制 rule=" + ruleId + " winner="
                            + sup.winnerRuleId() + " 原因=" + sup.reason());
                });
            }

            // 3) 胜出动作在沙箱内“生效”：与真实执行相同的校验与结论分类
            for (ResolvedAction ra : resolved) {
                if (!session.budget()) {
                    return;
                }
                CandidateAction winner = ra.winner();
                Rule rule = matched.keySet().stream()
                        .filter(r -> r.ruleId().equals(winner.ruleId()))
                        .findFirst().orElseThrow();
                globallyFired.add(winner.ruleId());
                session.firedRules.add(winner.ruleId());
                Event sourceEvent = matched.get(rule);

                Device target = deviceRegistry.find(winner.deviceId());
                if (target == null) {
                    session.step("动作目标设备不存在 rule=" + winner.ruleId()
                            + " device=" + winner.deviceId() + "，跳过");
                    continue;
                }
                // 越界约束与真实执行一致：跨家庭动作确定拒绝
                if (!rule.householdId().equals(target.householdId())) {
                    String reason = "SCOPE_DENIED(ACTION_TARGET_OUT_OF_SCOPE): 规则 "
                            + rule.ruleId() + "（家庭 " + rule.householdId()
                            + "）的动作目标设备 " + target.deviceId() + " 属于家庭 "
                            + target.householdId() + "，跨家庭动作被拒绝";
                    session.suppressed.put(winner.ruleId(), reason);
                    session.step("动作越界被拒绝 rule=" + winner.ruleId() + " " + reason);
                    continue;
                }
                DeviceModel model;
                try {
                    model = modelRegistry.modelFor(target);
                } catch (IllegalArgumentException ex) {
                    session.step("动作目标模型不支持 rule=" + winner.ruleId() + " "
                            + ex.getMessage());
                    continue;
                }
                // 模型/参数校验与真实执行完全一致（复用 CommandService 同一套校验）
                CommandRequest probe = new CommandRequest("dryrun-probe",
                        target.deviceId(), target.householdId(), winner.command(),
                        winner.params() == null ? Map.of() : winner.params(),
                        "dryrun", winner.ruleId(), 0L);
                try {
                    commandService.validateAgainstModel(target, probe);
                } catch (CommandService.CommandRejectedException ex) {
                    session.step("动作未生效 rule=" + winner.ruleId()
                            + " outcome=REJECTED reason=" + ex.reason()
                            + " detail=" + ex.getMessage() + "，不产生后续事件");
                    continue;
                }
                // 离线设备：明确给出不会生效的结论，不假装成功
                SandboxEntry targetEntry = session.state.get(target.deviceId());
                if (targetEntry != null && !targetEntry.online) {
                    session.step("动作不会生效 rule=" + winner.ruleId()
                            + " device=" + target.deviceId()
                            + " outcome=FAILED reason=DEVICE_OFFLINE"
                            + "（设备当前离线），链路在此终止");
                    continue;
                }

                // 生效：结果属性计算与模拟驱动一致（目标属性 + 参数值/常量结果）
                String targetAttr = model.commandTarget().get(winner.command());
                Object value = winner.params() == null
                        ? null : winner.params().get("value");
                if (value == null) {
                    value = model.commandResult().get(winner.command());
                }
                Map<String, Object> resulting = (targetAttr == null || value == null)
                        ? Map.of() : Map.of(targetAttr, value);
                Map<String, Object> changed = new LinkedHashMap<>();
                if (targetEntry != null) {
                    for (Map.Entry<String, Object> e : resulting.entrySet()) {
                        Object old = targetEntry.attributes.get(e.getKey());
                        if (!valueEquals(old, e.getValue())) {
                            changed.put(e.getKey(), e.getValue());
                        }
                        targetEntry.attributes.put(e.getKey(), e.getValue());
                    }
                    targetEntry.assignedSeq = targetEntry.assignedSeq + 1;
                    targetEntry.reportedSeq = targetEntry.assignedSeq;
                }
                session.step("动作生效 rule=" + winner.ruleId() + " device="
                        + target.deviceId() + " command=" + winner.command()
                        + " 结果属性=" + resulting + "，继续下一层传播");

                // 与真实 RuleEngine 一致：生效动作总是产生下一层事件
                if (targetAttr != null && targetEntry != null) {
                    Map<String, Object> eventAttrs = Map.of(targetAttr,
                            targetEntry.attributes.get(targetAttr));
                    List<String> newPath = new ArrayList<>(sourceEvent.path());
                    newPath.add(winner.ruleId());
                    nextLevel.add(new Event(target, eventAttrs, newPath));
                }
            }

            currentLevel = nextLevel;
        }
    }

    /** 与 RuleEngine 相同：条件树是否绑定到指定设备。 */
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

    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    /**
     * 报告 id 由输入与基线状态确定：相同输入（含相同判定时间）得到相同 id，
     * 便于调用方比对两次推演是否一致；不依赖随机数或墙钟。
     */
    private String reportId(DryRunRequest request, long evalTime, int maxDepth,
                            int maxNodes, int maxEntries, Session session,
                            List<Device> devices) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.householdId()).append('|').append(evalTime)
                .append('|').append(maxDepth).append(',').append(maxNodes)
                .append(',').append(maxEntries);
        for (Device device : devices) {
            SandboxEntry entry = session.state.get(device.deviceId());
            canonical.append('|').append(device.deviceId()).append('=')
                    .append(new TreeMap<>(entry.attributes)).append(',')
                    .append(entry.online);
        }
        List<DryRunRequest.StateFragment> fragments = request.fragments() == null
                ? List.of() : request.fragments();
        for (DryRunRequest.StateFragment f : fragments) {
            canonical.append('|').append(f.deviceId()).append(',')
                    .append(f.seq()).append(',').append(f.online()).append(',')
                    .append(f.attributes() == null
                            ? "{}" : new TreeMap<>(f.attributes()));
        }
        return "dryrun-" + Integer.toHexString(canonical.toString().hashCode());
    }
}
