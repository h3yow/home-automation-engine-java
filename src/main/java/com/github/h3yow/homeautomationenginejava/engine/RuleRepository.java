package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Rule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则仓库（内存实现）。
 *
 * <p>读多写少：保存/删除在 {@link #rules} 上互斥；每次读取返回规则不可变对象本身
 * （record 不可变），列表按 (priority, ruleId) 给出确定顺序，使裁决不依赖写入/遍历顺序。
 */
@Component
public class RuleRepository {

    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    /** 新增或更新（按 ruleId 幂等）。跨家庭修改由 Facade 先授权再校验 ruleId 归属。 */
    public void save(Rule rule) {
        if (rule == null || rule.ruleId() == null || rule.homeId() == null) {
            throw new IllegalArgumentException("rule/ruleId/homeId required");
        }
        synchronized (writeLock) {
            rules.put(rule.ruleId(), rule);
        }
    }

    public boolean delete(String ruleId) {
        synchronized (writeLock) {
            return rules.remove(ruleId) != null;
        }
    }

    public Optional<Rule> find(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    /** 某家庭全部启用与否的规则，确定顺序：priority 升序，再 ruleId 字典序。 */
    public List<Rule> allInHome(String homeId) {
        return rules.values().stream()
                .filter(r -> r.homeId().equals(homeId))
                .sorted(Comparator.comparingInt(Rule::priority)
                        .thenComparing(Rule::ruleId))
                .toList();
    }

    /** 仅启用的规则（规则引擎评估使用），同样确定顺序。 */
    public List<Rule> enabledInHome(String homeId) {
        return allInHome(homeId).stream().filter(Rule::enabled).toList();
    }
}
