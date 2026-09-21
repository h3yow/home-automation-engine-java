package com.github.h3yow.homeautomationenginejava.rule;

/**
 * 单属性状态条件：attr 与期望值按 op 比较。
 * deviceId 为 null 时对触发设备取属性，否则要求上下文设备与之一致。
 */
public record StateCondition(String deviceId, String attr, Operator op, Object expected)
        implements Condition {

    public enum Operator { EQ, NE, GT, LT }

    @Override
    public boolean evaluate(TriggerContext ctx) {
        if (deviceId != null && !deviceId.equals(ctx.deviceId())) {
            return false;
        }
        Object actual = ctx.snapshot().attributes().get(attr);
        if (actual == null) {
            // 缺失属性不猜测：相等判定为 false，不等判定为 true（语义确定）
            return op == Operator.NE;
        }
        return switch (op) {
            case EQ -> valueEquals(actual, expected);
            case NE -> !valueEquals(actual, expected);
            case GT -> compareOrdered(actual, expected) > 0;
            case LT -> compareOrdered(actual, expected) < 0;
        };
    }

    private static boolean valueEquals(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number e) {
            return a.doubleValue() == e.doubleValue();
        }
        // JSON 反序列化布尔/字符串与模型值做宽松但确定的归一化比较
        if (actual instanceof Boolean || expected instanceof Boolean) {
            return Boolean.valueOf(actual.toString()).equals(Boolean.valueOf(expected.toString()));
        }
        return actual.toString().equals(expected.toString());
    }

    private static int compareOrdered(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number e) {
            return Double.compare(a.doubleValue(), e.doubleValue());
        }
        return actual.toString().compareTo(expected.toString());
    }

    @Override
    public String describe() {
        String target = deviceId == null ? "*" : deviceId;
        return target + "." + attr + " " + op + " " + expected;
    }
}
