package com.github.h3yow.homeautomationenginejava.rule;

import java.util.List;

/**
 * 自动化规则。条件满足时按动作列表依次下发命令。
 * priority 数值越大优先级越高（冲突裁决使用）；同优先级按 ruleId 字典序，保证确定性。
 */
public record Rule(
        String ruleId,
        String householdId,
        String name,
        boolean enabled,
        int priority,
        Condition condition,
        List<RuleAction> actions
) {
}
