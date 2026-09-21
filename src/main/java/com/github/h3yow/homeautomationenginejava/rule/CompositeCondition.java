package com.github.h3yow.homeautomationenginejava.rule;

import java.util.List;

/**
 * 组合条件：ALL(AND) / ANY(OR) / NOT。
 */
public record CompositeCondition(Logic logic, List<Condition> children) implements Condition {

    public enum Logic { ALL, ANY, NOT }

    @Override
    public boolean evaluate(TriggerContext ctx) {
        // TODO 骨架
        return false;
    }

    @Override
    public String describe() {
        return logic + " " + children.stream().map(Condition::describe).toList();
    }
}
