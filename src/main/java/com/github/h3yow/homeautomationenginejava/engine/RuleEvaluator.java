package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleCondition;
import com.github.h3yow.homeautomationenginejava.model.StateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 条件求值（只读已提交快照，绝不读半更新）：
 * <ul>
 *   <li>StatePredicate：deviceId 为 null 指向触发设备；跨设备谓词读最新收敛快照，
 *       仅允许同家庭（由规则动作授权一并约束，条件阶段不做跨家庭读）；</li>
 *   <li>TimeWindow：半开 [start,end)，支持跨午夜窗口（如 22:00-06:00）；</li>
 *   <li>Composite：AND/OR 短路求值；嵌套任意层。</li>
 * </ul>
 * 条件不满足返回 false，规则绝不触发。比较规则确定：数值按 double，其余按 equals。
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    private final DeviceRegistry registry;
    private final EngineClock clock;

    public RuleEvaluator(DeviceRegistry registry, EngineClock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public boolean evaluate(Rule rule, StateEvent trigger) {
        boolean result = evalCondition(rule.condition(), trigger);
        log.info("[RULE] eval rule={} home={} triggerDevice={} => {}",
                rule.ruleId(), rule.homeId(), trigger.deviceId(), result);
        return result;
    }

    boolean evalCondition(RuleCondition condition, StateEvent trigger) {
        if (condition == null) {
            // 无条件规则视为恒真（“任何状态变化都触发”需显式建模为 null）。
            return true;
        }
        if (condition instanceof RuleCondition.StatePredicate p) {
            return evalPredicate(p, trigger);
        }
        if (condition instanceof RuleCondition.TimeWindow w) {
            return inWindow(clock.minuteOfDay(), w.startMinuteOfDay(), w.endMinuteOfDay());
        }
        if (condition instanceof RuleCondition.Composite c) {
            return switch (c.logic()) {
                case AND -> c.children().stream()
                        .allMatch(ch -> evalCondition(ch, trigger));
                case OR -> c.children().stream()
                        .anyMatch(ch -> evalCondition(ch, trigger));
            };
        }
        return false;
    }

    private boolean evalPredicate(RuleCondition.StatePredicate p, StateEvent trigger) {
        DeviceState state = resolveState(p.deviceId(), trigger);
        if (state == null) {
            // 目标设备不存在：确定语义为“不满足”，绝不误触发。
            log.info("[RULE] predicate target missing device={}", p.deviceId());
            return false;
        }
        if (!state.homeId().equals(trigger.homeId())) {
            // 跨家庭谓词：拒绝读取，视为不满足（隔离在条件层同样生效）。
            log.info("[RULE] predicate cross-home blocked ruleTriggerHome={} targetHome={}",
                    trigger.homeId(), state.homeId());
            return false;
        }
        Object actual = state.property(p.property());
        return compare(actual, p.operator(), p.expected());
    }

    /** 取谓词目标设备最新已收敛快照：deviceId 为 null 表示触发设备。 */
    DeviceState resolveState(String deviceId, StateEvent trigger) {
        String target = deviceId == null ? trigger.deviceId() : deviceId;
        return registry.stateOf(target) == null ? null
                : registry.stateOf(target).snapshot();
    }

    /**
     * 半开区间判定，支持跨午夜：start<=now<end；start>end 时窗口跨过 24:00。
     * start==end 视为空窗口（永不命中），避免歧义。
     */
    static boolean inWindow(int now, int start, int end) {
        if (start == end) {
            return false;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private boolean compare(Object actual, RuleCondition.Operator op, Object expected) {
        if (actual == null) {
            // 属性不存在（模型默认值外）：除 NE 外均不成立，语义确定。
            return op == RuleCondition.Operator.NE && expected != null;
        }
        if (actual instanceof Number na && expected instanceof Number ne) {
            double a = na.doubleValue();
            double b = ne.doubleValue();
            return switch (op) {
                case EQ -> a == b;
                case NE -> a != b;
                case GT -> a > b;
                case GE -> a >= b;
                case LT -> a < b;
                case LE -> a <= b;
            };
        }
        int cmp = compareNonNumeric(actual, expected);
        return switch (op) {
            case EQ -> actual.equals(expected);
            case NE -> !actual.equals(expected);
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
        };
    }

    /** 非数值有序比较：Boolean(false&lt;true)、String 字典序、其余按 toString 字典序。 */
    private int compareNonNumeric(Object actual, Object expected) {
        if (actual instanceof Boolean ba && expected instanceof Boolean be) {
            return Boolean.compare(ba, be);
        }
        return actual.toString().compareTo(expected == null ? "" : expected.toString());
    }
}
