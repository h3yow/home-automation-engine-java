package com.github.h3yow.homeautomationenginejava.model;

import java.util.List;

/**
 * 自动化规则。
 * enabled=false 不参与评估；priority 数值越小优先级越高（冲突裁决确定性依据之一）。
 * 同优先级冲突时以 ruleId 字典序为最终裁决，保证结果不依赖遍历顺序。
 */
public record Rule(
        String ruleId,
        String homeId,
        String name,
        int priority,
        boolean enabled,
        RuleCondition condition,
        List<RuleAction> actions) {
}
