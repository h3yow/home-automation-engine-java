package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleTrace;
import com.github.h3yow.homeautomationenginejava.model.StateEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规则触发引擎。
 *
 * <p>核心模型：一次状态变化开启一条<b>传播</b>，传播在专属工作线程上顺序推进多跳，
 * 事件通过队列喂给该线程。动作经命令通道同步等待生效；生效产生的新事件（发生在
 * 命令执行线程）只做一件事：把事件投入所属传播的队列后立即返回，不持有任何设备锁。
 * 这样设备锁、命令队列、传播线程三者之间不存在环式等待。
 *
 * <ul>
 *   <li><b>去重执行</b>：firedOnce 跨整传播，同一规则在一次传播中最多执行一次；
 *       重复/无变化上报不产生事件，故同一变化不会重复执行动作；</li>
 *   <li><b>成环检测</b>：每跳维护当前规则路径，命中规则若已在路径中即判环，
 *       不再投递该动作并终止传播，cyclePath 指出成环规则序列；</li>
 *   <li><b>冲突裁决</b>：每跳候选动作交 ConflictResolver 全序裁决，抑制原因可查；</li>
 *   <li><b>边界</b>：动作为系统内部身份，仍经家庭授权，越界即 REJECTED 并记录。</li>
 * </ul>
 */
@Component
public class RuleEngine implements StateService.StateEventListener {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRepository repository;
    private final RuleEvaluator evaluator;
    private final ConflictResolver conflictResolver;
    private final StateService stateService;
    private final ActionDispatcher actionDispatcher;
    private final CommandGateway commandGateway;
    private final EngineClock clock;

    private final ThreadPoolExecutor propagationPool;
    private final Map<String, Propagation> active = new ConcurrentHashMap<>();
    /** 自动化命令 ID -> 派发该命令时的父规则链路径快照（跨跳环检测依据）。 */
    private final Map<String, List<String>> commandToPath = new ConcurrentHashMap<>();
    private final Map<String, RuleTrace> lastTraceByHome = new ConcurrentHashMap<>();
    /** 最近一次开启/接管传播的 CompletableFuture，供测试与 Facade 确定性等待。 */
    private final Map<String, CompletableFuture<RuleTrace>> propagationFuture =
            new ConcurrentHashMap<>();

    public RuleEngine(RuleRepository repository, RuleEvaluator evaluator,
                      ConflictResolver conflictResolver, StateService stateService,
                      ActionDispatcher actionDispatcher, CommandGateway commandGateway,
                      EngineClock clock) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.conflictResolver = conflictResolver;
        this.stateService = stateService;
        this.actionDispatcher = actionDispatcher;
        this.commandGateway = commandGateway;
        this.clock = clock;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "ha-propagation-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.propagationPool = new ThreadPoolExecutor(1, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), tf,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PostConstruct
    void registerAsListener() {
        stateService.addListener(this);
    }

    /** 手动/测试入口：开启一次全新传播并等待其结束。 */
    public RuleTrace triggerPropagation(StateEvent event) {
        Propagation p = startPropagation(event);
        return join(p);
    }

    private Propagation startPropagation(StateEvent event) {
        Propagation p = new Propagation(UUID.randomUUID().toString(),
                event.homeId(), event.deviceId());
        active.put(p.id, p);
        propagationFuture.put(p.id, p.completion);
        p.events.add(new HopEvent(event, List.of()));
        propagationPool.submit(() -> runLoop(p));
        return p;
    }

    /**
     * 等待一次传播结束（测试/Facade 使用），带超时保护。
     */
    public RuleTrace awaitPropagation(String propagationId, long timeoutMillis) {
        CompletableFuture<RuleTrace> f = propagationFuture.get(propagationId);
        if (f == null) {
            return null;
        }
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** 等待系统中所有在途传播结束（测试断言前调用）。 */
    public void awaitQuiescence(long timeoutMillis) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (active.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void onStateEvent(StateEvent event) {
        // 在命令执行线程或上报线程中被调用：只路由，不执行规则。
        // 传播归属由事件直接携带（命令派发前登记），杜绝跨传播命令映射误关联。
        String pid = event.propagationId();
        Propagation p = pid == null ? null : active.get(pid);
        if (p != null) {
            List<String> parentPath = event.causedByCommandId() == null
                    ? List.of()
                    : commandToPath.getOrDefault(event.causedByCommandId(), List.of());
            synchronized (p.quietLock) {
                p.events.add(new HopEvent(event, List.copyOf(parentPath)));
                p.pending.remove(event.causedByCommandId());
            }
        } else {
            // 外部上报/住户命令生效（无传播归属）：开启新传播。
            startPropagation(event);
        }
    }

    /* ---------------- 传播工作线程主循环 ---------------- */

    private void runLoop(Propagation p) {
        try {
            while (true) {
                HopEvent hop = p.events.poll(50, TimeUnit.MILLISECONDS);
                if (hop != null) {
                    if (p.cycleDetected) {
                        return;
                    }
                    runHop(hop, p);
                    if (p.cycleDetected) {
                        return;
                    }
                    continue;
                }
                // 静默判定（与事件入队/保留撤销同锁）：无在途保留且队列空才结束。
                boolean done;
                synchronized (p.quietLock) {
                    done = p.pending.isEmpty() && p.events.isEmpty();
                }
                if (done) {
                    finish(p, new RuleTrace(p.triggerDeviceId,
                            List.copyOf(p.evaluated), List.copyOf(p.firedOrder),
                            List.copyOf(p.suppressed), List.of(), false,
                            "propagation completed: fired=" + p.firedOrder.size()
                                    + ", suppressed=" + p.suppressed.size()));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把事件路由到进行中的传播队列并撤销一个在途保留。
     */
    private void routeTo(Propagation p, String commandId, HopEvent hop) {
        synchronized (p.quietLock) {
            p.events.add(hop);
            if (commandId != null) {
                p.pending.remove(commandId);
            }
        }
    }

    /**
     * 一跳：评估 -> 环检测 -> 冲突裁决 -> 顺序派发动作。
     *
     * <p>去重语义：队列中的每个 StateEvent 只被处理一次，因此同一状态变化不会重复执行
     * 动作；规则在链路后续“新的状态变化”上允许再次命中，但若该规则已在当前规则路径上，
     * 则判定成环并在派发前终止。
     * 动作派发同步等待终态；其生效事件已被其它线程投入 p.events（携带本跳路径），下一轮处理。
     */
    private void runHop(HopEvent hop, Propagation p) {
        StateEvent trigger = hop.event();
        List<String> pathPrefix = hop.parentPath();
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : repository.enabledInHome(trigger.homeId())) {
            // 触发范围：规则只由“其条件实际监控的设备”的状态变化触发。
            // 别的设备变化时，即使条件恰好成立也不触发——触发必须归因于被监控状态。
            java.util.Set<String> watched = watchedDevices(rule);
            if (!watched.contains("*") && !watched.contains(trigger.deviceId())) {
                continue;
            }
            p.evaluated.add(rule.ruleId());
            if (evaluator.evaluate(rule, trigger)) {
                matched.add(rule);
            }
        }

        // 成环检测放在动作派发之前：命中规则已在当前规则链路径上即终止，不再产生副作用。
        for (Rule rule : matched) {
            if (pathPrefix.contains(rule.ruleId())) {
                List<String> cyclePath = new ArrayList<>(pathPrefix);
                cyclePath.add(rule.ruleId());
                RuleTrace trace = new RuleTrace(trigger.deviceId(),
                        List.copyOf(p.evaluated), List.copyOf(p.firedOrder),
                        List.copyOf(p.suppressed), List.copyOf(cyclePath), true,
                        "rule cycle detected at '" + rule.ruleId()
                                + "'; chain terminated to prevent infinite actions");
                p.cycleDetected = true;
                finish(p, trace);
                log.warn("[RULE] CYCLE detected path={}", cyclePath);
                return;
            }
        }

        if (matched.isEmpty()) {
            return;
        }

        List<ConflictResolver.Candidate> candidates = new ArrayList<>();
        for (Rule rule : matched) {
            List<RuleAction> actions = rule.actions();
            for (int i = 0; i < actions.size(); i++) {
                candidates.add(new ConflictResolver.Candidate(rule, actions.get(i), i));
            }
        }
        ConflictResolver.ResolvedBatch batch = conflictResolver.resolve(candidates);
        p.suppressed.addAll(batch.suppressed());

        for (ConflictResolver.Candidate winner : batch.winners()) {
            Rule rule = winner.rule();
            RuleAction action = winner.action();
            // 子事件的父路径 = 本跳父路径 + 当前规则；同步快照供其它线程读取。
            List<String> childPath = new ArrayList<>(pathPrefix);
            childPath.add(rule.ruleId());
            DeviceCommand command = buildCommand(rule, action);
            commandGateway.attachPropagation(command.commandId(), p.id);
            commandToPath.put(command.commandId(), List.copyOf(childPath));
            p.firedOrder.add(rule.ruleId());

            // 派发前在 quietLock 内保留槽位；生效事件回调在同锁内“入队 + 撤保留”，
            // 保证工作线程永远不会看到“无槽位且队列空”但事件尚未入队的状态。
            synchronized (p.quietLock) {
                p.pending.add(command.commandId());
            }
            CommandResult result = actionDispatcher.dispatchAutomation(
                    rule.homeId(), rule.ruleId(), command);
            synchronized (p.quietLock) {
                // 未产生事件（拒绝/失败/超时/无变化）：回调不会撤保留，在此释放。
                p.pending.remove(command.commandId());
            }
            log.info("[RULE] fired rule={} path={} action device={} cmd={} outcome={} reason={} detail={}",
                    rule.ruleId(), childPath, action.targetDeviceId(), action.command(),
                    result.outcome(), result.rejectReason(), result.detail());
        }
    }

    private void finish(Propagation p, RuleTrace trace) {
        active.remove(p.id);
        lastTraceByHome.put(p.homeId, trace);
        p.completion.complete(trace);
        log.info("[RULE] propagation {} end trigger={} fired={} suppressed={} cycle={}",
                p.id, p.triggerDeviceId, trace.firedRuleIds(),
                trace.suppressed().size(), trace.cycle());
    }

    /**
     * 收集规则条件实际监控的设备集合：
     * StatePredicate 指定的设备（null 不预先限定，视为监控所有设备，由求值时取触发设备）；
     * TimeWindow 不监控设备；组合条件递归收集。
     */
    private java.util.Set<String> watchedDevices(Rule rule) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collectWatched(rule.condition(), out);
        return out;
    }

    private void collectWatched(com.github.h3yow.homeautomationenginejava.model.RuleCondition c,
                                java.util.Set<String> out) {
        if (c instanceof com.github.h3yow.homeautomationenginejava.model.RuleCondition.StatePredicate p) {
            if (p.deviceId() == null) {
                // null 表示“触发设备自身”：对任意设备事件都在范围内。
                out.add("*");
            } else {
                out.add(p.deviceId());
            }
        } else if (c instanceof com.github.h3yow.homeautomationenginejava.model.RuleCondition.Composite comp) {
            comp.children().forEach(ch -> collectWatched(ch, out));
        }
    }

    private RuleTrace join(Propagation p) {
        try {
            return p.completion.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return lastTraceByHome.get(p.homeId);
        }
    }

    private DeviceCommand buildCommand(Rule rule, RuleAction action) {
        return new DeviceCommand(
                "auto-" + UUID.randomUUID(),
                action.targetDeviceId(),
                rule.homeId(),
                action.command(),
                action.params(),
                "system:rule:" + rule.ruleId(),
                rule.ruleId(),
                clock.nowMillis(),
                0L);
    }

    public RuleTrace lastTrace(String homeId) {
        return lastTraceByHome.get(homeId);
    }

    /** 一次传播的可变上下文。 */
    private static final class Propagation {
        final String id;
        final String homeId;
        final String triggerDeviceId;
        final LinkedBlockingQueue<HopEvent> events = new LinkedBlockingQueue<>(64);
        final Set<String> evaluated = new LinkedHashSet<>();
        final Set<String> firedOrder = new LinkedHashSet<>();
        final List<RuleTrace.SuppressedAction> suppressed =
                java.util.Collections.synchronizedList(new ArrayList<>());
        /** 已派发但尚未观察到生效事件的自动化命令 ID（静默判定依据）。 */
        final Set<String> pending = new LinkedHashSet<>();
        /** 保护 events/pending 联合状态的锁。 */
        final Object quietLock = new Object();
        final CompletableFuture<RuleTrace> completion = new CompletableFuture<>();
        volatile boolean cycleDetected;

        Propagation(String id, String homeId, String triggerDeviceId) {
            this.id = id;
            this.homeId = homeId;
            this.triggerDeviceId = triggerDeviceId;
        }
    }

    /** 队列中的一跳：待处理事件 + 引发该事件的父规则链路径快照。 */
    private record HopEvent(StateEvent event, List<String> parentPath) {
    }
}
