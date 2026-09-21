package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.ConflictResolver;
import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.ExecutionLog;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 执行记录查询入口：按家庭/设备过滤，越权访问由 AccessControl 拒绝。
 * 抑制原因查询用于解释“为什么某规则的动作没有执行”。
 */
@RestController
@RequestMapping("/api/records")
public class LogController {

    private final AccessControl accessControl;
    private final ExecutionLog executionLog;
    private final DeviceRegistry deviceRegistry;
    private final ConflictResolver conflictResolver;

    public LogController(AccessControl accessControl, ExecutionLog executionLog,
                         DeviceRegistry deviceRegistry, ConflictResolver conflictResolver) {
        this.accessControl = accessControl;
        this.executionLog = executionLog;
        this.deviceRegistry = deviceRegistry;
        this.conflictResolver = conflictResolver;
    }

    @GetMapping
    public List<ExecutionRecord> list(@RequestHeader(value = "X-Principal-Id", required = false)
                                      String principalId,
                                      @RequestParam(required = false) String deviceId,
                                      @RequestParam(required = false) String householdId,
                                      @RequestParam(defaultValue = "50") int limit) {
        var principal = accessControl.requirePrincipal(principalId);
        String scopeHousehold;
        if (deviceId != null) {
            accessControl.requireDeviceAccess(principalId, deviceId);
            Device device = deviceRegistry.get(deviceId);
            scopeHousehold = device.householdId();
            return executionLog.listByDevice(scopeHousehold, deviceId, limit);
        }
        if (householdId != null) {
            accessControl.requireHouseholdScope(principalId, householdId);
            scopeHousehold = householdId;
        } else {
            scopeHousehold = principal.householdIds().stream().sorted().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "INVALID_REQUEST: 身份不属于任何家庭"));
        }
        return executionLog.listByHousehold(scopeHousehold, limit);
    }

    @GetMapping("/{recordId}")
    public ExecutionRecord get(@RequestHeader(value = "X-Principal-Id", required = false)
                               String principalId,
                               @PathVariable String recordId) {
        // 记录 id 本身不含家庭信息：在调用者所属各家庭中查找，找不到即拒绝
        var principal = accessControl.requirePrincipal(principalId);
        for (String householdId : principal.householdIds()) {
            ExecutionRecord record = executionLog.get(householdId, recordId);
            if (record != null) {
                return record;
            }
        }
        throw new IllegalArgumentException("UNKNOWN_RECORD: 执行记录不存在或无权访问 " + recordId);
    }

    @GetMapping("/suppressions/{ruleId}")
    public Map<String, Object> suppression(@RequestHeader(value = "X-Principal-Id",
                                                   required = false) String principalId,
                                           @PathVariable String ruleId) {
        accessControl.requireRuleAccess(principalId, ruleId);
        String reason = conflictResolver.suppressionReason(ruleId);
        if (reason == null) {
            return Map.of("ruleId", ruleId, "suppressed", false);
        }
        return Map.of("ruleId", ruleId, "suppressed", true, "reason", reason);
    }
}
