package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.EngineClock;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import com.github.h3yow.homeautomationenginejava.rule.CompositeCondition;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import com.github.h3yow.homeautomationenginejava.rule.TimeWindowCondition;
import com.github.h3yow.homeautomationenginejava.rule.TriggerContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间窗口与组合条件单测：使用固定时间戳，断言窗口边界与跨午夜语义确定，不依赖运行时刻。
 */
class TimeWindowConditionTest {

    private long millisAt(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 22, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private TriggerContext ctxAt(long t) {
        return new TriggerContext("d1", "H1",
                new StateSnapshot("d1", 1, true, Map.of(), t), Map.of(), t);
    }

    @Test
    void windowBoundariesAndCrossingMidnightAreDeterministic() {
        // 09:00 - 18:00 普通窗口：左闭右开
        TimeWindowCondition day = new TimeWindowCondition(9 * 3600, 18 * 3600);
        assertTrue(day.evaluate(ctxAt(millisAt(9, 0))), "窗口起始边界包含");
        assertTrue(day.evaluate(ctxAt(millisAt(12, 0))));
        assertFalse(day.evaluate(ctxAt(millisAt(18, 0))), "窗口结束边界不包含(右开)");
        assertFalse(day.evaluate(ctxAt(millisAt(20, 0))));

        // 22:00 - 06:00 跨午夜
        TimeWindowCondition night = new TimeWindowCondition(22 * 3600, 6 * 3600);
        assertTrue(night.evaluate(ctxAt(millisAt(22, 0))));
        assertTrue(night.evaluate(ctxAt(millisAt(2, 0))), "凌晨必须落在跨午夜窗口内");
        assertFalse(night.evaluate(ctxAt(millisAt(12, 0))));
        assertFalse(night.evaluate(ctxAt(millisAt(6, 0))), "结束边界右开");

        // start == end 视为空窗口，恒不满足
        TimeWindowCondition empty = new TimeWindowCondition(0, 0);
        assertFalse(empty.evaluate(ctxAt(millisAt(0, 0))));

        // 越界边界直接构造失败（明确拒绝非法定义）
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWindowCondition(-1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWindowCondition(0, 86400));
    }

    @Test
    void compositeAndStateConditionsEvaluateDeterministically() {
        StateCondition hot = new StateCondition("thermo", "currentTemp",
                StateCondition.Operator.GT, 28);
        TimeWindowCondition night = new TimeWindowCondition(22 * 3600, 6 * 3600);
        var hotAndNight = new CompositeCondition(CompositeCondition.Logic.ALL,
                List.of(hot, night));

        TriggerContext nightHot = ctxWith("thermo", Map.of("currentTemp", 30),
                millisAt(23, 0));
        TriggerContext dayHot = ctxWith("thermo", Map.of("currentTemp", 30),
                millisAt(12, 0));
        assertTrue(hotAndNight.evaluate(nightHot), "高温且夜间应满足组合条件");
        assertFalse(hotAndNight.evaluate(dayHot), "高温但白天不满足");

        // 缺失属性：EQ=false, NE=true（确定语义，不猜值）
        StateCondition isOn = new StateCondition("d1", "power",
                StateCondition.Operator.EQ, "on");
        StateCondition isNotOn = new StateCondition("d1", "power",
                StateCondition.Operator.NE, "on");
        TriggerContext missing = ctxAt(millisAt(12, 0));
        assertFalse(isOn.evaluate(missing), "缺失属性 EQ 必须为 false");
        assertTrue(isNotOn.evaluate(missing), "缺失属性 NE 必须为 true");

        // NOT 必须且只能包含一个子条件，否则构造失败
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeCondition(CompositeCondition.Logic.NOT, List.of()));
    }

    private TriggerContext ctxWith(String device, Map<String, Object> attrs, long t) {
        return new TriggerContext(device, "H1",
                new StateSnapshot(device, 1, true, attrs, t), attrs, t);
    }
}
