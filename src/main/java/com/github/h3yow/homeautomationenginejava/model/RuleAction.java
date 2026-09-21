package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 规则命中后执行的动作：向目标设备下发命令。
 * 动作命令只允许落在规则所属家庭内。
 */
public record RuleAction(
        String targetDeviceId,
        String command,
        Map<String, Object> params) {
}
