package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.StateEvent;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 状态收敛核心。
 *
 * <p>不变量：
 * <ol>
 *   <li>每个设备一把锁：上报、生效确认串行化，杜绝半更新与更新丢失；</li>
 *   <li>版本只进不退：设备自报版本必须大于当前版本，重复/乱序一律 STALE_REPORT，
 *       已生效状态不允许无解释回退；</li>
 *   <li>真实变化才出事件：属性无差异（或仅重复确认）不产生 StateEvent，
 *       因而同一状态变化在一次传播中不会重复执行动作；</li>
 *   <li>离线是独立在线位，离线/失败绝不会被写成属性意义上的“成功”；</li>
 *   <li>事件监听器在锁外、同步执行，避免规则链中对同设备重入取锁，
 *       同时保证“上报返回时规则链已处理完”的可解释顺序。</li>
 * </ol>
 */
@Component
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    private final DeviceRegistry registry;
    private final ModelValidator modelValidator;
    private final EngineClock clock;

    /** 每设备串行锁，striped：设备集合有限且生命周期长。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 已生效命令集合：重复生效确认幂等忽略，不重复产生副作用。 */
    private final Map<String, String> effectiveCommands = new ConcurrentHashMap<>();

    private final List<StateEventListener> listeners = new CopyOnWriteArrayList<>();

    public StateService(DeviceRegistry registry, ModelValidator modelValidator, EngineClock clock) {
        this.registry = registry;
        this.modelValidator = modelValidator;
        this.clock = clock;
    }

    /**
     * 注册状态事件监听器。故意不使用 @Autowired 注入，以打断
     * StateService 与 RuleEngine 之间的构造期循环依赖；
     * 生产侧由 RuleEngine 的 @PostConstruct 自注册，测试侧手工注册。
     */
    public void addListener(StateEventListener listener) {
        listeners.add(listener);
    }

    private ReentrantLock lockOf(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }

    /**
     * 设备上报入口。
     */
    public ReportResult report(StateReport report) {
        if (report == null || report.deviceId() == null) {
            return reject(null, RejectReason.INVALID_PAYLOAD, "null report/deviceId");
        }
        String deviceId = report.deviceId();
        if (!registry.exists(deviceId)) {
            return reject(deviceId, RejectReason.DEVICE_NOT_FOUND,
                    "device not registered: " + deviceId);
        }

        // 模型校验先于加锁：未知能力/类型非法明确拒绝，不进入状态区。
        ModelValidator.Validation validation =
                modelValidator.validateReport(deviceId, report.properties());
        if (!validation.ok()) {
            return reject(deviceId, validation.reason(), validation.detail());
        }
        Map<String, Object> patch = validation.normalized();

        ReentrantLock lock = lockOf(deviceId);
        lock.lock();
        ReportResult result;
        StateEvent pendingEvent;
        try {
            DeviceState state = registry.stateOf(deviceId);
            long requested = report.reportedVersion() == null
                    // 旧版本模型不带版本号：服务端确定性赋“当前+1”，语义明确可解释。
                    ? state.version() + 1
                    : report.reportedVersion();
            if (requested <= state.version()) {
                log.info("[STATE] reject stale report device={} incoming={} current={}",
                        deviceId, requested, state.version());
                return reject(deviceId, RejectReason.STALE_REPORT,
                        "incoming version " + requested + " <= current " + state.version()
                                + " (duplicate or out-of-order report)");
            }

            Map<String, Object> oldProps = new LinkedHashMap<>(state.properties());
            Map<String, Object> changed = diff(oldProps, patch);
            boolean onlineChanged = report.online() != null
                    && report.online() != state.online();

            if (!onlineChanged && changed.isEmpty()) {
                // 版本前进但无实质变化：接受版本前进（设备心跳语义），但不出事件、不触发规则。
                state.apply(requested, report.online(), null, null, clock.nowMillis());
                log.info("[STATE] heartbeat device={} version={} no-property-change",
                        deviceId, requested);
                return new ReportResult(true, null,
                        "accepted (no property change)", requested, null);
            }

            state.apply(requested, report.online(), patch, null, clock.nowMillis());
            pendingEvent = new StateEvent(deviceId, state.homeId(),
                    requested - 1, requested,
                    Map.copyOf(oldProps), Map.copyOf(new LinkedHashMap<>(state.properties())),
                    state.online(), clock.nowMillis());
            log.info("[STATE] accepted device={} version={} changed={} online={}",
                    deviceId, requested, changed.keySet(), state.online());
            result = new ReportResult(true, null, "accepted", requested, pendingEvent);
        } finally {
            lock.unlock();
        }
        // 事件回调必须在锁外：规则链可能跨设备下发命令并等待其状态收敛，
        // 持锁同步回调会与设备串行区形成循环等待。
        dispatchEvent(pendingEvent);
        return result;
    }

    /**
     * 命令生效确认（由命令通道在模拟设备成功执行后调用）。
     *
     * <p>幂等语义：同一 commandId 的重复确认直接返回成功结果但 event=null，
     * 不重复写状态、不重复触发规则链，因此重试不会产生重复副作用。
     */
    public ReportResult confirmEffect(String deviceId, String commandId, long requestedVersion,
                                      Map<String, Object> targetProperties,
                                      String propagationId) {
        if (!registry.exists(deviceId)) {
            return reject(deviceId, RejectReason.DEVICE_NOT_FOUND,
                    "device not registered: " + deviceId);
        }
        ModelValidator.Validation validation =
                modelValidator.validateReport(deviceId, targetProperties);
        if (!validation.ok()) {
            return reject(deviceId, validation.reason(), validation.detail());
        }
        Map<String, Object> patch = validation.normalized();

        ReentrantLock lock = lockOf(deviceId);
        lock.lock();
        ReportResult result;
        StateEvent pendingEvent = null;
        try {
            DeviceState state = registry.stateOf(deviceId);
            if (commandId != null && commandId.equals(effectiveCommands.get(deviceId))) {
                log.info("[STATE] duplicate effect confirmation ignored device={} command={}",
                        deviceId, commandId);
                return new ReportResult(true, null,
                        "duplicate effect confirmation (idempotent)", state.version(), null);
            }
            long requested = requestedVersion <= state.version()
                    ? state.version() + 1 : requestedVersion;
            if (requestedVersion <= state.version()) {
                // 通道版本落后：不允许回退，按服务端单调版本前进（确认仍有效）。
                log.info("[STATE] effect confirmation version normalized device={} incoming={} -> {}",
                        deviceId, requestedVersion, requested);
            }

            Map<String, Object> oldProps = new LinkedHashMap<>(state.properties());
            Map<String, Object> changed = diff(oldProps, patch);
            if (changed.isEmpty()) {
                state.apply(requested, true, null, commandId, clock.nowMillis());
                if (commandId != null) {
                    effectiveCommands.put(deviceId, commandId);
                }
                return new ReportResult(true, null,
                        "effective but state already converged", requested, null);
            }
            state.apply(requested, true, patch, commandId, clock.nowMillis());
            if (commandId != null) {
                effectiveCommands.put(deviceId, commandId);
            }
            pendingEvent = new StateEvent(deviceId, state.homeId(),
                    requested - 1, requested,
                    Map.copyOf(oldProps), Map.copyOf(new LinkedHashMap<>(state.properties())),
                    true, clock.nowMillis(), commandId, propagationId);
            log.info("[STATE] effective device={} command={} version={} changed={}",
                    deviceId, commandId, requested, changed.keySet());
            result = new ReportResult(true, null, "effective", requested, pendingEvent);
        } finally {
            lock.unlock();
        }
        dispatchEvent(pendingEvent);
        return result;
    }

    public DeviceState snapshot(String deviceId) {
        DeviceState state = registry.stateOf(deviceId);
        return state == null ? null : state.snapshot();
    }

    /** 只在调用方持锁时调用；比较以等号为准（数字族按数值比较，避免 1 与 1L 假差异）。 */
    private Map<String, Object> diff(Map<String, Object> oldProps, Map<String, Object> patch) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object old = oldProps.get(e.getKey());
            if (!valueEquals(old, e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        return changed;
    }

    private boolean valueEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return java.util.Objects.equals(a, b);
    }

    /**
     * 在设备锁外同步派发事件。同步语义保证“上报返回时该跳规则已处理完”，
     * 规则引擎内部会把链式传播转到独立传播线程，避免跨设备循环等待。
     */
    private void dispatchEvent(StateEvent event) {
        for (StateEventListener listener : listeners) {
            listener.onStateEvent(event);
        }
    }

    private ReportResult reject(String deviceId, RejectReason reason, String detail) {
        long version = deviceId == null ? -1L
                : (registry.stateOf(deviceId) == null ? -1L : registry.stateOf(deviceId).version());
        log.info("[STATE] rejected device={} reason={} detail={}", deviceId, reason, detail);
        return new ReportResult(false, reason, detail, version, null);
    }

    /** 状态事件监听器（规则引擎实现）。 */
    public interface StateEventListener {
        void onStateEvent(StateEvent event);
    }
}
