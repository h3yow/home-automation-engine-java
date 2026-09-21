package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 命令的一次结论。rejectReason / suppressReason 提供可解释依据；
 * mergedIntoCommandId 表示被合并到的在队命令。
 */
public record CommandResult(
        String commandId,
        String deviceId,
        CommandOutcome outcome,
        RejectReason rejectReason,
        String detail,
        String suppressReason,
        String mergedIntoCommandId,
        Map<String, Object> effectiveAttributes,
        long timestamp
) {
}
