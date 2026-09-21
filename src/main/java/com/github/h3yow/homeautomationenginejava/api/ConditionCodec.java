package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.rule.CompositeCondition;
import com.github.h3yow.homeautomationenginejava.rule.Condition;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import com.github.h3yow.homeautomationenginejava.rule.TimeWindowCondition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则条件 JSON 编解码。未知条件类型/运算符一律抛错（明确拒绝，不静默忽略）。
 *
 * 支持形态：
 * {"type":"state","device":"d1","attr":"power","op":"EQ","value":"on"}
 * {"type":"time","start":79200,"end":93600}
 * {"type":"all","children":[...]} / {"type":"any",...} / {"type":"not","children":[...]}
 */
@Component
public class ConditionCodec {

    public Condition decode(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("条件必须是对象: " + raw);
        }
        Object typeObj = map.get("type");
        if (!(typeObj instanceof String type)) {
            throw new IllegalArgumentException("条件缺少 type 字段");
        }
        return switch (type) {
            case "state" -> decodeState(map);
            case "time" -> decodeTime(map);
            case "all", "any", "not" -> decodeComposite(type, map);
            default -> throw new IllegalArgumentException("未知条件类型: " + type
                    + "（支持 state/time/all/any/not）");
        };
    }

    private Condition decodeState(Map<?, ?> map) {
        String device = asString(map.get("device"));
        String attr = requireString(map, "attr");
        String opRaw = requireString(map, "op");
        StateCondition.Operator op;
        try {
            op = StateCondition.Operator.valueOf(opRaw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知比较运算符: " + opRaw
                    + "（支持 EQ/NE/GT/LT）");
        }
        if (!map.containsKey("value")) {
            throw new IllegalArgumentException("state 条件缺少 value 字段");
        }
        return new StateCondition(device, attr, op, map.get("value"));
    }

    private Condition decodeTime(Map<?, ?> map) {
        Integer start = asInt(map.get("start"));
        Integer end = asInt(map.get("end"));
        if (start == null || end == null) {
            throw new IllegalArgumentException("time 条件缺少 start/end（当日秒数）");
        }
        return new TimeWindowCondition(start, end);
    }

    private Condition decodeComposite(String type, Map<?, ?> map) {
        Object childrenRaw = map.get("children");
        if (!(childrenRaw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(type + " 条件需要非空 children 数组");
        }
        List<Condition> children = new ArrayList<>();
        for (Object child : list) {
            children.add(decode(child));
        }
        CompositeCondition.Logic logic = CompositeCondition.Logic.valueOf(type.toUpperCase());
        return new CompositeCondition(logic, children);
    }

    private String requireString(Map<?, ?> map, String key) {
        String value = asString(map.get(key));
        if (value == null) {
            throw new IllegalArgumentException("条件缺少字符串字段: " + key);
        }
        return value;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
