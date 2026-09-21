package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleTrace;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 统一入口：REST、测试、后续其他入口全部收敛到这里，保证结论一致、可解释。
 *
 * <p>固定处理顺序：解析主体 -> 家庭/角色授权（拒绝原因可区分） -> 领域校验 ->
 * 执行 -> 留痕。设备上报属于设备入口，只接受已注册设备（设备自带家庭绑定）。
 */
@Component
public class HomeAutomationFacade {

    private final DeviceRegistry registry;
    private final AccessControl accessControl;
    private final CommandGateway commandGateway;
    private final StateService stateService;
    private final RuleEngine ruleEngine;
    private final RuleRepository ruleRepository;
    private final AuditLog auditLog;
    private final EngineClock clock;

    public HomeAutomationFacade(DeviceRegistry registry, AccessControl accessControl,
                                CommandGateway commandGateway, StateService stateService,
                                RuleEngine ruleEngine, RuleRepository ruleRepository,
                                AuditLog auditLog, EngineClock clock) {
        this.registry = registry;
        this.accessControl = accessControl;
        this.commandGateway = commandGateway;
        this.stateService = stateService;
        this.ruleEngine = ruleEngine;
        this.ruleRepository = ruleRepository;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /* ---------------- 设备入口：状态上报 ---------------- */

    public ReportResult report(StateReport report) {
        // 设备已注册是进入状态收敛的前提；未注册由 StateService 给 DEVICE_NOT_FOUND。
        return stateService.report(report);
    }

    /* ---------------- 住户入口：命令 ---------------- */

    public CommandResult dispatch(Resident resident, DeviceCommand command) {
        String deviceHome = registry.homeOfDevice(command.deviceId());
        AccessControl.Authorization auth = accessControl.authorize(
                resident, deviceHome, AccessControl.Action.WRITE_DEVICE);
        if (!auth.allowed()) {
            return rejectedCommand(command, auth.reason(),
                    "dispatch denied: " + auth.detail());
        }
        DeviceCommand withHome = new DeviceCommand(
                command.commandId() == null ? "cmd-" + UUID.randomUUID() : command.commandId(),
                command.deviceId(), deviceHome, command.command(), command.params(),
                resident.residentId(), null, clock.nowMillis(), command.dedupKey());
        return commandGateway.dispatch(withHome);
    }

    public CommandResult result(Resident resident, String commandId) {
        CommandResult result = commandGateway.getResult(commandId);
        if (result == null) {
            return null;
        }
        String home = registry.homeOfDevice(result.deviceId());
        AccessControl.Authorization auth = accessControl.authorize(
                resident, home, AccessControl.Action.READ_DEVICE);
        if (!auth.allowed()) {
            return new CommandResult(commandId, result.deviceId(),
                    CommandOutcome.REJECTED, auth.reason(),
                    "result query denied: " + auth.detail(), result.version(), clock.nowMillis());
        }
        return result;
    }

    public DeviceState state(Resident resident, String deviceId) {
        String home = registry.homeOfDevice(deviceId);
        AccessControl.Authorization auth = accessControl.authorize(
                resident, home, AccessControl.Action.READ_DEVICE);
        if (!auth.allowed()) {
            return null;
        }
        return stateService.snapshot(deviceId);
    }

    /* ---------------- 住户入口：规则管理（OWNER） ---------------- */

    public Rule saveRule(Resident resident, Rule rule) {
        // 更新已有规则：按既有规则所属家庭授权；新建：按提交的 homeId 且必须是自己家庭。
        String targetHome = ruleRepository.find(rule.ruleId())
                .map(Rule::homeId)
                .orElse(rule.homeId());
        if (!resident.homeId().equals(targetHome) || !resident.homeId().equals(rule.homeId())) {
            return null;
        }
        AccessControl.Authorization auth = accessControl.authorize(
                resident, targetHome, AccessControl.Action.MANAGE_RULE);
        if (!auth.allowed()) {
            return null;
        }
        ruleRepository.save(rule);
        return rule;
    }

    public boolean deleteRule(Resident resident, String ruleId) {
        Rule existing = ruleRepository.find(ruleId).orElse(null);
        if (existing == null) {
            return false;
        }
        AccessControl.Authorization auth = accessControl.authorize(
                resident, existing.homeId(), AccessControl.Action.MANAGE_RULE);
        if (!auth.allowed()) {
            return false;
        }
        return ruleRepository.delete(ruleId);
    }

    public List<Rule> listRules(Resident resident) {
        return ruleRepository.allInHome(resident.homeId()).stream()
                // VIEWER 也可查看本家庭规则（只读）；其他家庭在查询层即不可见。
                .filter(r -> accessControl.authorize(resident, r.homeId(),
                        AccessControl.Action.READ_DEVICE).allowed())
                .toList();
    }

    /* ---------------- 住户入口：执行记录 ---------------- */

    public List<ExecutionRecord> queryLogs(Resident resident, String deviceId, int limit) {
        if (deviceId != null && registry.exists(deviceId)) {
            AccessControl.Authorization auth = accessControl.authorize(resident,
                    registry.homeOfDevice(deviceId), AccessControl.Action.READ_LOG);
            if (!auth.allowed()) {
                return List.of();
            }
        }
        return auditLog.query(resident.homeId(), deviceId, limit);
    }

    /* ---------------- 规则追踪 ---------------- */

    public RuleTrace lastTrace(Resident resident) {
        // 追踪链路只暴露本家庭。
        return ruleEngine.lastTrace(resident.homeId());
    }

    /** 供系统/测试等待所有在途规则传播结束后再断言。 */
    public void awaitQuiet(long timeoutMillis) {
        ruleEngine.awaitQuiescence(timeoutMillis);
    }

    private CommandResult rejectedCommand(DeviceCommand command,
                                          com.github.h3yow.homeautomationenginejava.model.RejectReason reason,
                                          String detail) {
        return new CommandResult(command.commandId(), command.deviceId(),
                CommandOutcome.REJECTED, reason, detail, -1L, clock.nowMillis());
    }
}
