package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.model.RuleCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则条件 JSON 解析：结构非法必须明确报错（HTTP 400），不静默降级为无条件。
 *
 * <p>支持结构：
 * <pre>
 * {"type":"state","deviceId":null,"property":"power","operator":"EQ","value":true}
 * {"type":"time","start":"22:00","end":"06:00"}
 * {"type":"and"|"or","children":[ ... ]}
 * </pre>
 */
public final class ConditionParser {

    private ConditionParser() { }

    @SuppressWarnings("unchecked")
    public static RuleCondition parse(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        String type = String.valueOf(m.get("type"));
        return switch (type) {
            case "state" -> new RuleCondition.StatePredicate(
                    (String) m.get("deviceId"),
                    required(m, "property"),
                    RuleCondition.Operator.valueOf(required(m, "operator")),
                    m.get("value"));
            case "time" -> new RuleCondition.TimeWindow(
                    toMinutes(required(m, "start")),
                    toMinutes(required(m, "end")));
            case "and", "or" -> {
                List<Object> children = (List<Object>) m.getOrDefault("children", List.of());
                List<RuleCondition> parsed = new ArrayList<>();
                for (Object child : children) {
                    parsed.add(parse(child));
                }
                yield new RuleCondition.Composite(
                        RuleCondition.Logic.valueOf(type.toUpperCase()), List.copyOf(parsed));
            }
            default -> throw new IllegalArgumentException("unknown condition type: " + type);
        };
    }

    private static String required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("condition missing field: " + key);
        }
        return String.valueOf(v);
    }

    /** "HH:mm" -> 一天内分钟数。 */
    static int toMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("bad time format: " + hhmm);
        }
        int h = Integer.parseInt(parts[0].trim());
        int min = Integer.parseInt(parts[1].trim());
        if (h < 0 || h > 23 || min < 0 || min > 59) {
            throw new IllegalArgumentException("time out of range: " + hhmm);
        }
        return h * 60 + min;
    }
}
