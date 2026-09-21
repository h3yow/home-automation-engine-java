package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.RuleRegistry;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则管理入口。创建/更新要求调用者属于规则所在家庭；
 * 动作目标设备的越界在规则触发时由 AccessControl 再次强制校验（纵深防御）。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final AccessControl accessControl;
    private final RuleRegistry ruleRegistry;
    private final ConditionCodec conditionCodec;

    public RuleController(AccessControl accessControl, RuleRegistry ruleRegistry,
                          ConditionCodec conditionCodec) {
        this.accessControl = accessControl;
        this.ruleRegistry = ruleRegistry;
        this.conditionCodec = conditionCodec;
    }

    @PutMapping("/{ruleId}")
    public Dtos.RuleView upsert(@RequestHeader(value = "X-Principal-Id", required = false)
                                String principalId,
                                @PathVariable String ruleId,
                                @RequestBody Dtos.RuleBody body) {
        if (body.householdId() == null || body.householdId().isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST: 规则必须指定 householdId");
        }
        accessControl.requireHouseholdScope(principalId, body.householdId());
        if (body.actions() == null || body.actions().isEmpty()) {
            throw new IllegalArgumentException("INVALID_REQUEST: 规则至少包含一个动作");
        }
        var condition = conditionCodec.decode(body.condition());
        List<RuleAction> actions = new ArrayList<>();
        for (Dtos.ActionBody a : body.actions()) {
            if (a.deviceId() == null || a.command() == null) {
                throw new IllegalArgumentException(
                        "INVALID_REQUEST: 动作必须包含 deviceId 与 command");
            }
            actions.add(new RuleAction(a.deviceId(), a.command(),
                    a.params() == null ? Map.of() : a.params()));
        }
        Rule rule = new Rule(ruleId, body.householdId(),
                body.name() == null ? ruleId : body.name(),
                body.enabled() == null || body.enabled(),
                body.priority() == null ? 0 : body.priority(),
                condition, actions);
        ruleRegistry.upsert(rule);
        return toView(rule);
    }

    @GetMapping("/{ruleId}")
    public Dtos.RuleView get(@RequestHeader(value = "X-Principal-Id", required = false)
                             String principalId,
                             @PathVariable String ruleId) {
        accessControl.requireRuleAccess(principalId, ruleId);
        return toView(ruleRegistry.get(ruleId));
    }

    @GetMapping
    public List<Dtos.RuleView> list(@RequestHeader(value = "X-Principal-Id", required = false)
                                    String principalId,
                                    @RequestParam(required = false) String householdId) {
        var principal = accessControl.requirePrincipal(principalId);
        if (householdId != null) {
            accessControl.requireHouseholdScope(principalId, householdId);
            return ruleRegistry.listByHousehold(householdId).stream()
                    .map(this::toView).toList();
        }
        return principal.householdIds().stream()
                .sorted()
                .flatMap(h -> ruleRegistry.listByHousehold(h).stream())
                .map(this::toView)
                .toList();
    }

    @DeleteMapping("/{ruleId}")
    public Map<String, Object> delete(@RequestHeader(value = "X-Principal-Id", required = false)
                                      String principalId,
                                      @PathVariable String ruleId) {
        accessControl.requireRuleAccess(principalId, ruleId);
        boolean removed = ruleRegistry.remove(ruleId);
        return Map.of("ruleId", ruleId, "removed", removed);
    }

    private Dtos.RuleView toView(Rule rule) {
        List<Dtos.ActionBody> actions = new ArrayList<>();
        for (RuleAction a : rule.actions()) {
            actions.add(new Dtos.ActionBody(a.deviceId(), a.command(), a.params()));
        }
        return new Dtos.RuleView(rule.ruleId(), rule.name(), rule.householdId(),
                rule.enabled(), rule.priority(), rule.condition(), actions);
    }
}
