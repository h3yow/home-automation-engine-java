package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 冲突裁决后的动作：winner 为胜出候选；suppressedWithReason 记录每个被抑制候选的规则与原因。
 */
public record ResolvedAction(
        CandidateAction winner,
        Map<String, Suppression> suppressedWithReason
) {
    /**
     * 被抑制候选及可查询的具体原因。
     */
    public record Suppression(String suppressedRuleId, String winnerRuleId, String reason) {
    }
}
