package com.github.h3yow.homeautomationenginejava.rule;

import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;

import java.util.Map;

/**
 * 一次状态变化事件的上下文：触发设备、变化后的快照、变化的属性、当前时间。
 */
public record TriggerContext(
        String deviceId,
        String householdId,
        StateSnapshot snapshot,
        Map<String, Object> changedAttributes,
        long eventTime
) {
}
