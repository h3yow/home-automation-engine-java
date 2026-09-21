package com.github.h3yow.homeautomationenginejava.rule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 组合条件：ALL(AND) / ANY(OR) / NOT(单子)。
 */
public record CompositeCondition(Logic logic, List<Condition> children) implements Condition {

    public enum Logic { ALL, ANY, NOT }

    public CompositeCondition {
        children = List.copyOf(children);
        if (logic == Logic.NOT && children.size() != 1) {
            throw new IllegalArgumentException("NOT 组合条件必须且只能包含一个子条件");
        }
        if ((logic == Logic.ALL || logic == Logic.ANY) && children.isEmpty()) {
            throw new IllegalArgumentException(logic + " 组合条件至少包含一个子条件");
        }
    }

    @Override
    public boolean evaluate(TriggerContext ctx) {
        return switch (logic) {
            case ALL -> children.stream().allMatch(c -> c.evaluate(ctx));
            case ANY -> children.stream().anyMatch(c -> c.evaluate(ctx));
            case NOT -> !children.get(0).evaluate(ctx);
        };
    }

    @Override
    public String describe() {
        String inner = children.stream().map(Condition::describe)
                .collect(Collectors.joining(", "));
        return logic + "(" + inner + ")";
    }
}
