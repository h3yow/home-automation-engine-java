package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 状态收敛器：
 * 1) 校验上报（未知能力/缺字段/模型版本），不合法明确拒绝；
 * 2) 按 seq 单调收敛，乱序/重复上报不得让已生效状态无解释回退；
 * 3) 离线/失败上报只改 online，不覆盖属性；
 * 4) 合法状态变化后通知监听器（规则引擎）。
 */
@Service
public class StateConvergence {

    public void addChangeListener(BiConsumer<Device, StateSnapshot> listener) {
        // TODO 骨架
    }

    public ReportResult report(Device device, Long seq, boolean online,
                               Map<String, Object> attributes) {
        // TODO 骨架
        return null;
    }

    public StateSnapshot snapshot(String deviceId) {
        // TODO 骨架
        return null;
    }

    /** 命令生效确认：仅当状态尚未被更新的上报覆盖时，按期望值收敛并触发后续传播。 */
    public StateSnapshot confirmEffective(String deviceId, Map<String, Object> expectedAttributes) {
        // TODO 骨架
        return null;
    }

    public long nextSeq(String deviceId) {
        // TODO 骨架
        return 0L;
    }
}
