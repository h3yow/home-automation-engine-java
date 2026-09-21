package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CandidateAction;
import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.ResolvedAction;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import com.github.h3yow.homeautomationenginejava.rule.PropagationResult;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import com.github.h3yow.homeautomationenginejava.rule.RuleTrace;
import com.github.h3yow.homeautomationenginejava.rule.TriggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规则引擎。
 *
 * 一次事件传播的不变量：
 * - 同一规则在一次传播中最多触发一次（fired 集合去重），同一状态变化不重复执行动作；
 * - 按 BFS 层级推进：本层全部匹配规则先做冲突裁决，胜出动作下发并等待结论，
 *   只有真正生效的动作产生下一层事件；
 * - 维护规则路径栈，候选规则已在当前路径上时判定成环：立即终止整条传播，
 *   trace 指出成环规则序列；
 * - 规则动作越界（跨家庭）被 AccessControl 拒绝，记录原因但不影响其他规则；
 * - 所有评估、裁决、下发步骤写入 {@link RuleTrace}，作为可解释追踪链路。
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRegistry ruleRegistry;
    private final DeviceRegistry deviceRegistry;
    private final ModelRegistry modelRegistry;
    private final StateConvergence stateConvergence;
    private final ConflictResolver conflictResolver;
    private final CommandService commandService;
    private final EngineClock clock;

    public RuleEngine(RuleRegistry ruleRegistry, DeviceRegistry deviceRegistry,
                      ModelRegistry modelRegistry, StateConvergence stateConvergence,
                      ConflictResolver conflictResolver, CommandService commandService,
                      EngineClock clock) {
        this.ruleRegistry = ruleRegistry;
        this.deviceRegistry = deviceRegistry;
        this.modelRegistry = modelRegistry;
        this.stateConvergence = stateConvergence;
        this.conflictResolver = conflictResolver;
        this.commandService = commandService;
        this.clock = clock;
    }

    public PropagationResult propagate(Device rootDevice, StateSnapshot rootSnapshot) {
        String eventId = "evt-" + UUID.randomUUID();
        RuleTrace trace = new RuleTrace(eventId);
        List<String> firedRules = new ArrayList<>();
        java.util.Set<String> globallyFired = new java.util.HashSet<>();
        List<String> dispatched = new ArrayList<>();
        Map<String, String> suppressed = new LinkedHashMap<>();

        record Event(Device device, StateSnapshot snapshot, List<String> path) {
        }
        List<Event> currentLevel = new ArrayList<>();
        currentLevel.add(new Event(rootDevice, rootSnapshot, List.of()));

        trace.step("根事件 device=" + rootDevice.deviceId()
                + " 变化属性=" + rootSnapshot.attributes());
        log.info("[规则传播-开始] event={} device={} changed={}",
                eventId, rootDevice.deviceId(), rootSnapshot.attributes());

        int depth = 0;
        while (!currentLevel.isEmpty()) {
            depth++;
            List<Event> nextLevel = new ArrayList<>();

            // 1) 收集本层所有匹配规则（候选），保证跨事件的冲突也能统一裁决
            Map<Rule, Event> matched = new LinkedHashMap<>();
            for (Event event : currentLevel) {
                StateSnapshot full = stateConvergence.snapshot(event.device().deviceId());
                StateSnapshot base = full == null ? event.snapshot() : full;
                TriggerContext ctx = new TriggerContext(
                        event.device().deviceId(), event.device().householdId(),
                        base, event.snapshot().attributes(), clock.nowMillis());

                for (Rule rule : ruleRegistry.allEnabled()) {
                    if (!rule.householdId().equals(event.device().householdId())) {
                        continue; // 规则只作用于本家庭
                    }
                    if (matched.containsKey(rule)) {
                        continue; // 本层已被另一事件匹配
                    }
                    // 一次传播去重：已在本传播任意层触发过的规则不再执行
                    if (globallyFired.contains(rule.ruleId())) {
                        trace.step("规则 " + rule.ruleId() + " 本次传播已触发过，去重跳过");
                        continue;
                    }
                    // 环检测：规则已在当前触发路径上
                    if (event.path().contains(rule.ruleId())) {
                        List<String> cyclePath = new ArrayList<>(event.path());
                        cyclePath.add(rule.ruleId());
                        trace.markCycle(cyclePath);
                        trace.step("检测到规则环: " + cyclePath + "，终止传播");
                        log.warn("[规则传播-成环] event={} 环={}", eventId, cyclePath);
                        return new PropagationResult(eventId, List.copyOf(firedRules),
                                List.copyOf(dispatched), Map.copyOf(suppressed),
                                List.copyOf(cyclePath), trace);
                    }
                    boolean satisfied;
                    try {
                        satisfied = rule.condition().evaluate(ctx);
                    } catch (RuntimeException ex) {
                        trace.step("规则 " + rule.ruleId() + " 条件评估异常: " + ex.getMessage());
                        log.warn("[规则评估-异常] rule={}", rule.ruleId(), ex);
                        continue;
                    }
                    trace.step("评估规则 " + rule.ruleId() + " [" + rule.name()
                            + "] priority=" + rule.priority() + " 触发设备="
                            + event.device().deviceId() + " 条件["
                            + rule.condition().describe() + "]=" + satisfied
                            + (satisfied ? " => 命中" : " => 不满足，不触发"));
                    if (satisfied) {
                        matched.put(rule, event);
                    }
                }
            }

            if (matched.isEmpty()) {
                trace.step("第 " + depth + " 层无命中规则，传播收敛结束");
                break;
            }

            // 2) 候选动作（一条规则可有多动作，按声明顺序）
            List<CandidateAction> candidates = new ArrayList<>();
            for (Map.Entry<Rule, Event> e : matched.entrySet()) {
                Rule rule = e.getKey();
                for (RuleAction action : rule.actions()) {
                    candidates.add(new CandidateAction(rule.ruleId(), rule.householdId(),
                            action.deviceId(), action.command(), action.params(),
                            rule.priority()));
                }
            }

            // 3) 冲突裁决（确定性）
            List<ResolvedAction> resolved = conflictResolver.resolve(candidates);
            for (ResolvedAction ra : resolved) {
                ra.suppressedWithReason().forEach((ruleId, sup) -> {
                    suppressed.put(ruleId, sup.reason());
                    trace.step("动作被抑制 rule=" + ruleId + " winner="
                            + sup.winnerRuleId() + " 原因=" + sup.reason());
                });
            }

            // 4) 下发胜出动作，等待结论；生效动作的结果事件进入下一层
            for (ResolvedAction ra : resolved) {
                CandidateAction winner = ra.winner();
                Rule rule = matched.keySet().stream()
                        .filter(r -> r.ruleId().equals(winner.ruleId()))
                        .findFirst().orElseThrow();
                globallyFired.add(winner.ruleId());
                Event sourceEvent = matched.get(rule);
                firedRules.add(winner.ruleId());

                Device target = deviceRegistry.find(winner.deviceId());
                if (target == null) {
                    trace.step("动作目标设备不存在 rule=" + winner.ruleId()
                            + " device=" + winner.deviceId() + "，跳过");
                    continue;
                }
                DeviceModel model;
                try {
                    model = modelRegistry.modelFor(target);
                } catch (IllegalArgumentException ex) {
                    trace.step("动作目标模型不支持 rule=" + winner.ruleId() + " "
                            + ex.getMessage());
                    continue;
                }
                String targetAttr = model.commandTarget().get(winner.command());

                String commandId = "cmd-" + UUID.randomUUID();
                String idemKey = eventId + ":" + winner.ruleId() + ":"
                        + winner.deviceId() + ":" + winner.command();
                CommandRequest request = new CommandRequest(
                        commandId, winner.deviceId(), winner.householdId(),
                        winner.command(), winner.params(), idemKey,
                        winner.ruleId(), 0L);
                trace.step("下发动作 rule=" + winner.ruleId() + " device="
                        + winner.deviceId() + " command=" + winner.command()
                        + " params=" + winner.params() + " commandId=" + commandId);

                CommandService.SubmitOutcome outcome;
                try {
                    outcome = commandService.submitFromRule(request);
                } catch (AccessDeniedException ex) {
                    suppressed.put(winner.ruleId(),
                            "SCOPE_DENIED(" + ex.reason() + "): " + ex.getMessage());
                    trace.step("动作越界被拒绝 rule=" + winner.ruleId() + " 原因="
                            + ex.reason() + " " + ex.getMessage());
                    log.warn("[规则动作-越界] rule={} reason={}",
                            winner.ruleId(), ex.reason());
                    continue;
                }

                if (outcome.terminal() && outcome.result().outcome()
                        != CommandOutcome.EFFECTIVE) {
                    trace.step("动作未生效 rule=" + winner.ruleId() + " outcome="
                            + outcome.result().outcome() + " reason="
                            + outcome.result().rejectReason() + "，不产生后续事件");
                    continue;
                }

                CommandResult terminal = commandService.awaitResult(commandId, 5000);
                dispatched.add(commandId);
                if (terminal == null || terminal.outcome() != CommandOutcome.EFFECTIVE) {
                    CommandResult t = terminal == null ? outcome.result() : terminal;
                    trace.step("动作结论非生效 rule=" + winner.ruleId() + " outcome="
                            + t.outcome() + " reason=" + t.rejectReason()
                            + "，链路在此终止");
                    continue;
                }
                trace.step("动作生效 rule=" + winner.ruleId() + " commandId="
                        + commandId + "，继续下一层传播");

                StateSnapshot after = stateConvergence.snapshot(winner.deviceId());
                if (after != null && targetAttr != null) {
                    StateSnapshot changeEvent = new StateSnapshot(winner.deviceId(),
                            after.seq(), true,
                            Map.of(targetAttr, after.attributes().get(targetAttr)),
                            clock.nowMillis());
                    List<String> newPath = new ArrayList<>(sourceEvent.path());
                    newPath.add(winner.ruleId());
                    nextLevel.add(new Event(target, changeEvent, newPath));
                }
            }

            currentLevel = nextLevel;
        }

        trace.step("传播正常结束 event=" + eventId + " 触发规则=" + firedRules);
        log.info("[规则传播-结束] event={} fired={} dispatched={} suppressed={}",
                eventId, firedRules, dispatched, suppressed.keySet());
        return new PropagationResult(eventId, List.copyOf(firedRules),
                List.copyOf(dispatched), Map.copyOf(suppressed), null, trace);
    }
}
