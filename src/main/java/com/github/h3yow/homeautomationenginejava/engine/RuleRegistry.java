package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.rule.Rule;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则登记处（内存，并发安全）。
 */
@Service
public class RuleRegistry {

    public void upsert(Rule rule) {
        // TODO 骨架
    }

    public Rule get(String ruleId) {
        // TODO 骨架
        return null;
    }

    public boolean remove(String ruleId) {
        // TODO 骨架
        return false;
    }

    public List<Rule> listByHousehold(String householdId) {
        // TODO 骨架
        return List.of();
    }

    public List<Rule> allEnabled() {
        // TODO 骨架
        return List.of();
    }
}
