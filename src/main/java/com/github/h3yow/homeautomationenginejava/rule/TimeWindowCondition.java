package com.github.h3yow.homeautomationenginejava.rule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 时间窗口条件：事件时间落在 [startSecondOfDay, endSecondOfDay)。
 * start == end 视为空窗口（恒不满足）；start > end 表示跨午夜窗口（如 22:00-06:00）。
 * 统一按系统默认时区计算日秒，语义确定。
 */
public record TimeWindowCondition(int startSecondOfDay, int endSecondOfDay) implements Condition {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    public TimeWindowCondition {
        if (startSecondOfDay < 0 || startSecondOfDay >= 86400
                || endSecondOfDay < 0 || endSecondOfDay >= 86400) {
            throw new IllegalArgumentException("时间窗口边界必须落在 [0,86400) 秒内");
        }
    }

    @Override
    public boolean evaluate(TriggerContext ctx) {
        int second = secondOfDay(ctx.eventTime());
        if (startSecondOfDay == endSecondOfDay) {
            return false;
        }
        if (startSecondOfDay < endSecondOfDay) {
            return second >= startSecondOfDay && second < endSecondOfDay;
        }
        // 跨午夜
        return second >= startSecondOfDay || second < endSecondOfDay;
    }

    private static int secondOfDay(long epochMillis) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
        return zdt.getHour() * 3600 + zdt.getMinute() * 60 + zdt.getSecond();
    }

    @Override
    public String describe() {
        return "time-window[" + startSecondOfDay + "s," + endSecondOfDay + "s)";
    }
}
