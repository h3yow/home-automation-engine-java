package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 规则评估产出的候选动作。
 */
public record CandidateAction(
        String ruleId,
        String householdId,
        String deviceId,
        String command,
        Map<String, Object> params,
        int priority
) {
}
