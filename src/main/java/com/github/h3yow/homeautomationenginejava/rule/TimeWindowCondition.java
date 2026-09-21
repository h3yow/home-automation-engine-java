package com.github.h3yow.homeautomationenginejava.rule;

import com.github.h3yow.homeautomationenginejava.rule.Condition;
import com.github.h3yow.homeautomationenginejava.rule.TriggerContext;

/**
 * 时间窗口条件：事件时间落在 [startSecondOfDay, endSecondOfDay)。
 * 支持跨午夜窗口（start > end，如 22:00-06:00）。
 */
public record TimeWindowCondition(int startSecondOfDay, int endSecondOfDay) implements Condition {

    @Override
    public boolean evaluate(TriggerContext ctx) {
        // TODO 骨架
        return false;
    }

    @Override
    public String describe() {
        return "time-window[" + startSecondOfDay + "," + endSecondOfDay + ")";
    }
}
