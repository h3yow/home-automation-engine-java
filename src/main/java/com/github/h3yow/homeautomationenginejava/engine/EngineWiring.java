package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 引擎装配：把状态变化事件接入规则引擎。
 *
 * 上报线程在状态收敛的设备锁外同步调用本监听器，规则动作经每设备工作线程串行执行，
 * 同一设备的“上报 -> 规则 -> 生效”链路天然串行，避免并发入口产生交错的半更新。
 */
@Component
public class EngineWiring {

    private static final Logger log = LoggerFactory.getLogger(EngineWiring.class);

    public EngineWiring(StateConvergence stateConvergence, RuleEngine ruleEngine) {
        stateConvergence.addChangeListener((Device device, StateSnapshot change) -> {
            if (change.attributes().isEmpty()) {
                // 纯在线位翻转（如离线/恢复上线）不评估规则
                return;
            }
            try {
                ruleEngine.propagate(device, change);
            } catch (RuntimeException ex) {
                log.error("[状态监听] 规则传播异常 device={}", device.deviceId(), ex);
            }
        });
    }
}
