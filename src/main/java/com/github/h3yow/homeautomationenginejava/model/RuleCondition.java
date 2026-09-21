package com.github.h3yow.homeautomationenginejava.model;

import java.util.List;

/**
 * 规则条件（密封类型）：设备状态比较、时间窗口、组合条件。
 * 求值逻辑在 RuleEvaluator 中实现，此层只承载结构。
 */
public sealed interface RuleCondition
        permits RuleCondition.StatePredicate, RuleCondition.TimeWindow, RuleCondition.Composite {

    /**
     * 状态谓词：deviceId 为 null 时匹配触发设备自身。
     */
    record StatePredicate(String deviceId, String property, Operator operator, Object expected)
            implements RuleCondition {
    }

    /**
     * 半开时间窗口 [start,end)，按一天内分钟数给出，时区由引擎配置固定。
     */
    record TimeWindow(int startMinuteOfDay, int endMinuteOfDay) implements RuleCondition {
    }

    /**
     * 组合条件。
     */
    record Composite(Logic logic, List<RuleCondition> children) implements RuleCondition {
    }

    enum Operator { EQ, NE, GT, GE, LT, LE }

    enum Logic { AND, OR }
}
