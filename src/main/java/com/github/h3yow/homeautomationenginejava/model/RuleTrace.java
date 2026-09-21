package com.github.h3yow.homeautomationenginejava.model;

import java.util.List;

/**
 * 一次事件传播的规则追踪链路，可查询、可解释。
 * 当链式触发成环时 cycle=true，cyclePath 指出成环的规则序列。
 */
public record RuleTrace(
        String triggerDeviceId,
        List<String> evaluatedRuleIds,
        List<String> firedRuleIds,
        List<SuppressedAction> suppressed,
        List<String> cyclePath,
        boolean cycle,
        String detail) {

    /**
     * 被抑制动作及具体原因（CONFLICT_SUPPRESSED 时记录胜出者）。
     */
    public record SuppressedAction(
            String ruleId,
            String deviceId,
            String command,
            RejectReason reason,
            String detail) {
    }
}
