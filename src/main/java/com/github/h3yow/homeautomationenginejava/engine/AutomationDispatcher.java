package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 系统内部自动化动作的派发器。
 *
 * <p>自动化不享有特权：与住户命令一样先做家庭授权，规则家庭与目标设备家庭不一致时
 * 返回 REJECTED/CROSS_HOME_ACCESS 并写审计，绝不越界执行。
 */
@Component
public class AutomationDispatcher implements ActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AutomationDispatcher.class);

    private final AccessControl accessControl;
    private final CommandGateway commandGateway;
    private final AuditLog auditLog;
    private final EngineClock clock;

    public AutomationDispatcher(AccessControl accessControl, CommandGateway commandGateway,
                                AuditLog auditLog, EngineClock clock) {
        this.accessControl = accessControl;
        this.commandGateway = commandGateway;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    public CommandResult dispatchAutomation(String ruleHomeId, String ruleId,
                                            DeviceCommand command) {
        AccessControl.Authorization auth =
                accessControl.authorizeAutomation(ruleHomeId, command.deviceId());
        if (!auth.allowed()) {
            CommandResult rejected = new CommandResult(command.commandId(),
                    command.deviceId(), CommandOutcome.REJECTED, auth.reason(),
                    "automation '" + ruleId + "' denied: " + auth.detail(),
                    -1L, clock.nowMillis());
            auditLog.record(new ExecutionRecord(
                    "exec-" + auditLog.nextSequence(),
                    ruleHomeId, ruleId, command.commandId(), command.deviceId(),
                    CommandOutcome.REJECTED, auth.reason(),
                    "[auto:" + ruleId + "] " + auth.detail(), clock.nowMillis()));
            log.warn("[AUTO] rule={} target={} rejected reason={} detail={}",
                    ruleId, command.deviceId(), auth.reason(), auth.detail());
            return rejected;
        }
        return commandGateway.dispatch(command);
    }
}
