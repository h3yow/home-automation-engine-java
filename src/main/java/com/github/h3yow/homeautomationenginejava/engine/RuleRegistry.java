package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.rule.Rule;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 规则登记处（内存，并发安全）。列举顺序固定（ruleId 字典序），保证评估与裁决确定性。
 */
@Service
public class RuleRegistry {

    private static final Comparator<Rule> ORDER =
            Comparator.comparingInt(Rule::priority).reversed()
                    .thenComparing(Rule::ruleId);

    private final ConcurrentMap<String, Rule> rules = new ConcurrentHashMap<>();

    public void upsert(Rule rule) {
        rules.put(rule.ruleId(), rule);
    }

    public Rule get(String ruleId) {
        return rules.get(ruleId);
    }

    public boolean remove(String ruleId) {
        return rules.remove(ruleId) != null;
    }

    public List<Rule> listByHousehold(String householdId) {
        return rules.values().stream()
                .filter(r -> r.householdId().equals(householdId))
                .sorted(ORDER)
                .toList();
    }

    public java.util.Set<String> allRuleIds() {
        return java.util.Set.copyOf(rules.keySet());
    }

    public void setEnabled(String ruleId, boolean enabled) {
        Rule r = rules.get(ruleId);
        if (r != null) {
            rules.put(ruleId, new Rule(r.ruleId(), r.householdId(), r.name(), enabled,
                    r.priority(), r.condition(), r.actions()));
        }
    }

    public List<Rule> allEnabled() {
        return rules.values().stream()
                .filter(Rule::enabled)
                .sorted(ORDER)
                .toList();
    }
}
