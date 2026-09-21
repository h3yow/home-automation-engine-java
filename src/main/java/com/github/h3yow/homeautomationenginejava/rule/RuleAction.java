package com.github.h3yow.homeautomationenginejava.rule;

import java.util.Map;

/**
 * 规则动作：对目标设备下发命令。
 */
public record RuleAction(
        String deviceId,
        String command,
        Map<String, Object> params
) {
}
