package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 一条设备命令。
 * sourceRuleId 非空表示由系统内部自动化触发（同样受家庭边界约束）。
 */
public record DeviceCommand(
        String commandId,
        String deviceId,
        String homeId,
        String command,
        Map<String, Object> params,
        String issuedBy,
        String sourceRuleId,
        long issuedAtMillis,
        long dedupKey) {

    public boolean isAutomation() { return sourceRuleId != null; }
}
