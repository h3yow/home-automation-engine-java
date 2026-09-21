package com.github.h3yow.homeautomationenginejava.rule;

/**
 * 单属性状态条件：attr 与期望值按 op 比较。
 */
public record StateCondition(String deviceId, String attr, Operator op, Object expected)
        implements Condition {

    public enum Operator { EQ, NE, GT, LT }

    @Override
    public boolean evaluate(TriggerContext ctx) {
        // TODO 骨架
        return false;
    }

    @Override
    public String describe() {
        return deviceId + "." + attr + " " + op + " " + expected;
    }
}
