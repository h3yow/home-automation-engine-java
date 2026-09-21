package com.github.h3yow.homeautomationenginejava.model;

/**
 * 执行记录：命令/动作的审计留痕，按家庭隔离查询。
 */
public record ExecutionRecord(
        String id,
        String homeId,
        String ruleId,
        String commandId,
        String deviceId,
        CommandOutcome outcome,
        RejectReason rejectReason,
        String detail,
        long atMillis) {
}
