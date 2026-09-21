package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import com.github.h3yow.homeautomationenginejava.rule.PropagationResult;
import org.springframework.stereotype.Service;

/**
 * 规则引擎：
 * 对每次实际状态变化评估规则；一次事件传播中同一规则只触发一次（去重）；
 * 链式触发时维护规则路径，成环立即终止并指出成环规则；
 * 候选动作经冲突裁决后下发；规则动作越界由 AccessControl 拒绝。
 */
@Service
public class RuleEngine {

    public PropagationResult propagate(Device rootDevice, StateSnapshot rootSnapshot) {
        // TODO 骨架
        return null;
    }
}
