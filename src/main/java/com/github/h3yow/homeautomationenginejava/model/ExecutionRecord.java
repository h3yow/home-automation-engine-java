package com.github.h3yow.homeautomationenginejava.model;

import java.util.List;

/**
 * 可审计执行记录：命令来源（用户/规则）、规则追踪链路、失败上下文。
 */
public record ExecutionRecord(
        String recordId,
        String householdId,
        String deviceId,
        String commandId,
        String sourceRuleId,
        String principalId,
        CommandOutcome outcome,
        RejectReason rejectReason,
        String detail,
        List<String> traceChain,
        long timestamp
) {
}
