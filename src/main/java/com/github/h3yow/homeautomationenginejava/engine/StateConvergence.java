package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 状态收敛器（线程安全）。
 *
 * 语义约定：
 * - 上报必须携带模型声明的必填字段；出现未知能力属性一律拒绝（不静默忽略）；
 * - seq 单调收敛：携带 seq 且不大于已收敛序号的上报为过期乱序，不采纳、状态不回退；
 * - 设备未携带 seq 时由收敛器在设备锁内分配严格递增序号；
 * - online=false 的离线/失败上报只更新在线位，不覆盖任何能力属性，也不会记为成功；
 * - 仅当能力属性实际发生变化（或在线位翻转）时产生新快照；监听器通知在锁外触发，
 *   与规则引擎的下发/等待形成确定的同步调用链，避免死锁。
 */
@Service
public class StateConvergence {

    private static final Logger log = LoggerFactory.getLogger(StateConvergence.class);

    private record Entry(StateSnapshot snapshot, long assignedSeq, long reportedSeq) {
    }

    /** 锁内计算结果，解锁后再通知监听器。 */
    private record Accepted(ReportResult result, StateSnapshot changeView) {
    }

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final List<BiConsumer<Device, StateSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final ModelRegistry modelRegistry;
    private final EngineClock clock;

    public StateConvergence(ModelRegistry modelRegistry, EngineClock clock) {
        this.modelRegistry = modelRegistry;
        this.clock = clock;
    }

    public void addChangeListener(BiConsumer<Device, StateSnapshot> listener) {
        listeners.add(listener);
    }

    public ReportResult report(Device device, Long seq, boolean online,
                               Map<String, Object> attributes) {
        Objects.requireNonNull(device, "device");
        Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
        DeviceModel model;
        try {
            model = modelRegistry.modelFor(device);
        } catch (IllegalArgumentException ex) {
            log.warn("[上报-拒绝] device={} 原因=UNSUPPORTED_MODEL_VERSION detail={}",
                    device.deviceId(), ex.getMessage());
            return new ReportResult(device.deviceId(), false,
                    RejectReason.UNSUPPORTED_MODEL_VERSION, ex.getMessage(), -1, Map.of());
        }

        Accepted accepted;
        ReportResult rejected;
        synchronized (lockFor(device.deviceId())) {
            Entry current = store.get(device.deviceId());

            List<String> unknown = attrs.keySet().stream()
                    .filter(k -> !model.capabilities().contains(k))
                    .sorted().toList();
            if (!unknown.isEmpty()) {
                String detail = "上报包含模型 " + model.modelId() + " v" + model.version()
                        + " 未知能力属性: " + unknown;
                log.warn("[上报-拒绝] device={} 原因=UNKNOWN_CAPABILITY {}",
                        device.deviceId(), detail);
                return reject(device, RejectReason.UNKNOWN_CAPABILITY, detail, current);
            }

            // 必填字段：携带能力属性的业务上报必须带齐；空属性上报视为心跳/在线翻转
            List<String> missing = attrs.isEmpty() ? List.of()
                    : model.requiredReportFields().stream()
                    .filter(f -> !attrs.containsKey(f))
                    .sorted().toList();
            if (!missing.isEmpty()) {
                String detail = "上报缺少必填字段: " + missing;
                log.warn("[上报-拒绝] device={} 原因=MISSING_REQUIRED_FIELD {}",
                        device.deviceId(), detail);
                return reject(device, RejectReason.MISSING_REQUIRED_FIELD, detail, current);
            }

            long assigned = (current == null ? 0 : current.assignedSeq()) + 1;
            long reportedSeq = seq == null ? assigned : seq;

            if (seq != null && current != null && reportedSeq <= current.reportedSeq()) {
                String detail = "上报序号 " + reportedSeq + " 不大于已收敛序号 "
                        + current.reportedSeq() + "，判定为乱序/重复，状态不回退";
                log.warn("[上报-过期] device={} 原因=OUTDATED_SEQUENCE {}",
                        device.deviceId(), detail);
                return new ReportResult(device.deviceId(), false, RejectReason.OUTDATED_SEQUENCE,
                        detail, current.snapshot().seq(), current.snapshot().attributes());
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
            StateSnapshot snapshot = new StateSnapshot(device.deviceId(), assigned, online,
                    finalAttrs, clock.nowMillis());
            store.put(device.deviceId(), new Entry(snapshot, assigned, reportedSeq));

            log.info("[上报-采纳] device={} assignedSeq={} reportedSeq={} online={} 变化属性={}",
                    device.deviceId(), assigned, reportedSeq, online, changed);

            ReportResult result = new ReportResult(device.deviceId(), true, null, null,
                    assigned, finalAttrs);
            StateSnapshot changeView = (!changed.isEmpty() || wasOnline != online)
                    ? new StateSnapshot(device.deviceId(), assigned, online,
                            Map.copyOf(changed), clock.nowMillis())
                    : null;
            accepted = new Accepted(result, changeView);
        }

        if (accepted.changeView() != null) {
            notifyListeners(device, accepted.changeView());
        }
        return accepted.result();
    }

    public StateSnapshot snapshot(String deviceId) {
        Entry e = store.get(deviceId);
        return e == null ? null : e.snapshot();
    }

    /**
     * 命令生效确认：在设备锁内把期望属性合并进当前状态并单调推进。
     * 通知同样在锁外由调用方（CommandService）按需触发规则链。
     * 返回变化视图；无变化返回 null。
     */
    public StateSnapshot confirmEffective(String deviceId, Map<String, Object> expectedAttributes) {
        synchronized (lockFor(deviceId)) {
            Entry current = store.get(deviceId);
            // 设备尚无快照（理论上注册即有基线快照；此处兜底）：以在线空状态初始化
            if (current == null) {
                StateSnapshot bootstrap = new StateSnapshot(deviceId, 0, true,
                        Map.of(), clock.nowMillis());
                current = new Entry(bootstrap, 0, 0);
                store.put(deviceId, current);
            }
            Map<String, Object> merged = new LinkedHashMap<>(current.snapshot().attributes());
            Map<String, Object> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : expectedAttributes.entrySet()) {
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
                    Map.copyOf(merged), clock.nowMillis());
            store.put(deviceId, new Entry(snapshot, assigned, assigned));
            log.info("[生效确认] device={} seq={} 变化属性={}", deviceId, assigned, changed);
            return new StateSnapshot(deviceId, assigned, true, Map.copyOf(changed),
                    clock.nowMillis());
        }
    }

    /** 测试隔离：清空设备快照（下一次上报视为初始状态）。 */
    public void resetDevice(String deviceId) {
        synchronized (lockFor(deviceId)) {
            store.remove(deviceId);
        }
    }

    public long nextSeq(String deviceId) {
        synchronized (lockFor(deviceId)) {
            Entry current = store.get(deviceId);
            return (current == null ? 0 : current.assignedSeq()) + 1;
        }
    }

    private void notifyListeners(Device device, StateSnapshot changeView) {
        for (BiConsumer<Device, StateSnapshot> listener : listeners) {
            listener.accept(device, changeView);
        }
    }

    private Object lockFor(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new Object());
    }

    private ReportResult reject(Device device, RejectReason reason, String detail,
                                Entry current) {
        Map<String, Object> latest = current == null
                ? Map.of() : new HashMap<>(current.snapshot().attributes());
        long seq = current == null ? -1 : current.snapshot().seq();
        return new ReportResult(device.deviceId(), false, reason, detail, seq, latest);
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }
}
