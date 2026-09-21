package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.HomeAutomationFacade;
import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleCondition;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP 入口：只做 DTO 转换，所有判定在 Facade/引擎中完成，
 * 保证 HTTP 与本地调用/自动化入口结论一致。
 */
@RestController
@RequestMapping("/api/ha")
public class HomeController {

    private final HomeAutomationFacade facade;
    private final DeviceRegistry registry;

    public HomeController(HomeAutomationFacade facade, DeviceRegistry registry) {
        this.facade = facade;
        this.registry = registry;
    }

    /** 规则条件结构非法：明确 400 拒绝，绝不静默按“无条件规则”执行。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorResponse> badCondition(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dtos.ErrorResponse("bad_request", "INVALID_PAYLOAD", e.getMessage()));
    }

    @PostMapping("/commands")
    public Dtos.ResultResponse dispatch(@RequestBody Dtos.DispatchRequest request) {
        Resident resident = registry.resident(request.residentId());
        DeviceCommand command = new DeviceCommand(
                request.commandId() == null ? "cmd-" + UUID.randomUUID() : request.commandId(),
                request.deviceId(), null, request.command(), request.params(),
                request.residentId(), null, System.currentTimeMillis(), 0L);
        CommandResult result = resident == null
                ? new CommandResult(command.commandId(), command.deviceId(),
                        CommandOutcome.REJECTED,
                        com.github.h3yow.homeautomationenginejava.model.RejectReason.INSUFFICIENT_ROLE,
                        "unknown resident: " + request.residentId(), -1L, System.currentTimeMillis())
                : facade.dispatch(resident, command);
        return toResponse(result);
    }

    @GetMapping("/commands/{id}")
    public ResponseEntity<Dtos.ResultResponse> result(
            @PathVariable String id, @RequestParam String residentId) {
        Resident resident = registry.resident(residentId);
        if (resident == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new Dtos.ResultResponse(false, CommandOutcome.REJECTED.name(),
                            "INSUFFICIENT_ROLE", "unknown resident", -1L));
        }
        CommandResult result = facade.result(resident, id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/reports")
    public Dtos.ReportResponse report(@RequestBody Dtos.ReportRequest request) {
        ReportResult result = facade.report(new StateReport(
                request.deviceId(), request.version(), request.online(), request.properties()));
        return new Dtos.ReportResponse(result.accepted(),
                result.reason() == null ? null : result.reason().name(),
                result.detail(), result.newVersion(), result.event() != null);
    }

    @PostMapping("/rules")
    public ResponseEntity<Object> saveRule(@RequestBody Dtos.RuleRequest request) {
        Resident resident = registry.resident(request.residentId());
        if (resident == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new Dtos.ErrorResponse("forbidden", "INSUFFICIENT_ROLE", "unknown resident"));
        }
        Rule rule = new Rule(request.ruleId(), resident.homeId(), request.name(),
                request.priority(), request.enabled(),
                ConditionParser.parse(request.condition()),
                parseActions(request.actions()));
        Rule saved = facade.saveRule(resident, rule);
        if (saved == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new Dtos.ErrorResponse("forbidden", "CROSS_HOME_ACCESS/INSUFFICIENT_ROLE",
                            "rule save denied"));
        }
        return ResponseEntity.ok(ruleToMap(saved));
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> listRules(@RequestParam String residentId) {
        Resident resident = registry.resident(residentId);
        if (resident == null) {
            return List.of();
        }
        return facade.listRules(resident).stream().map(HomeController::ruleToMap).toList();
    }

    @GetMapping("/devices/{id}/state")
    public ResponseEntity<Object> state(@PathVariable String id,
                                        @RequestParam String residentId) {
        Resident resident = registry.resident(residentId);
        if (resident == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var state = facade.state(resident, id);
        if (state == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new Dtos.ErrorResponse("forbidden", "CROSS_HOME_ACCESS",
                            "device not found or access denied"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", state.deviceId());
        body.put("homeId", state.homeId());
        body.put("online", state.online());
        body.put("version", state.version());
        body.put("properties", state.properties());
        body.put("lastAppliedCommandId", state.lastAppliedCommandId());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/logs")
    public Object logs(@RequestParam String residentId,
                       @RequestParam(required = false) String deviceId,
                       @RequestParam(defaultValue = "50") int limit) {
        Resident resident = registry.resident(residentId);
        if (resident == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new Dtos.ErrorResponse("forbidden", "INSUFFICIENT_ROLE", "unknown resident"));
        }
        return facade.queryLogs(resident, deviceId, limit);
    }

    @GetMapping("/trace")
    public Object trace(@RequestParam String residentId) {
        Resident resident = registry.resident(residentId);
        if (resident == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new Dtos.ErrorResponse("forbidden", "INSUFFICIENT_ROLE", "unknown resident"));
        }
        RuleTraceView view = RuleTraceView.of(facade.lastTrace(resident));
        return view == null
                ? ResponseEntity.ok(Map.of("trace", "none"))
                : ResponseEntity.ok(view);
    }

    private static Dtos.ResultResponse toResponse(CommandResult result) {
        return new Dtos.ResultResponse(
                result.outcome() == CommandOutcome.EFFECTIVE
                        || result.outcome() == CommandOutcome.ACCEPTED,
                result.outcome().name(),
                result.rejectReason() == null ? null : result.rejectReason().name(),
                result.detail(), result.version());
    }

    private static List<RuleAction> parseActions(List<Object> raw) {
        List<RuleAction> actions = new ArrayList<>();
        if (raw == null) {
            return actions;
        }
        for (Object o : raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) m.getOrDefault("params", Map.of());
            actions.add(new RuleAction((String) m.get("targetDeviceId"),
                    (String) m.get("command"), new LinkedHashMap<>(params)));
        }
        return actions;
    }

    static Map<String, Object> ruleToMap(Rule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", rule.ruleId());
        m.put("homeId", rule.homeId());
        m.put("name", rule.name());
        m.put("priority", rule.priority());
        m.put("enabled", rule.enabled());
        return m;
    }

    /** 规则追踪只读视图。 */
    public record RuleTraceView(String triggerDeviceId, List<String> evaluatedRuleIds,
                                List<String> firedRuleIds, List<Object> suppressed,
                                List<String> cyclePath, boolean cycle, String detail) {
        static RuleTraceView of(com.github.h3yow.homeautomationenginejava.model.RuleTrace t) {
            if (t == null) {
                return null;
            }
            return new RuleTraceView(t.triggerDeviceId(), t.evaluatedRuleIds(),
                    t.firedRuleIds(), t.suppressed().stream().map(s -> (Object)
                            Map.of("ruleId", s.ruleId(), "deviceId", s.deviceId(),
                                    "command", s.command(), "reason", s.reason().name(),
                                    "detail", s.detail())).toList(),
                    t.cyclePath(), t.cycle(), t.detail());
        }
    }
}
