package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.CommandService;
import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.SimulatedDeviceDriver;
import com.github.h3yow.homeautomationenginejava.engine.StateConvergence;
import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 设备入口：状态上报、状态查询、命令下发与结论查询、故障注入（测试用）。
 * 身份经 X-Principal-Id 头传递，所有操作先过 AccessControl。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final AccessControl accessControl;
    private final DeviceRegistry deviceRegistry;
    private final StateConvergence stateConvergence;
    private final CommandService commandService;
    private final SimulatedDeviceDriver driver;

    public DeviceController(AccessControl accessControl, DeviceRegistry deviceRegistry,
                            StateConvergence stateConvergence, CommandService commandService,
                            SimulatedDeviceDriver driver) {
        this.accessControl = accessControl;
        this.deviceRegistry = deviceRegistry;
        this.stateConvergence = stateConvergence;
        this.commandService = commandService;
        this.driver = driver;
    }

    @PostMapping("/{deviceId}/reports")
    public Map<String, Object> report(@RequestHeader(value = "X-Principal-Id", required = false)
                                      String principalId,
                                      @PathVariable String deviceId,
                                      @RequestBody Dtos.ReportRequest body) {
        accessControl.requireDeviceAccess(principalId, deviceId);
        Device device = deviceRegistry.get(deviceId);
        // 上报中的模型版本仅用于审计：设备身份以登记表为准；不支持的版本由收敛器明确拒绝
        boolean online = body.online() == null || body.online();
        ReportResult result = stateConvergence.report(device, body.seq(), online,
                body.attributes() == null ? Map.of() : body.attributes());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", result.deviceId());
        response.put("accepted", result.accepted());
        response.put("rejectReason", result.rejectReason());
        response.put("detail", result.detail());
        response.put("appliedSeq", result.appliedSeq());
        response.put("latestAttributes", result.latestAttributes());
        return response;
    }

    @org.springframework.web.bind.annotation.GetMapping("/{deviceId}")
    public Map<String, Object> get(@RequestHeader(value = "X-Principal-Id", required = false)
                                   String principalId,
                                   @PathVariable String deviceId) {
        accessControl.requireDeviceAccess(principalId, deviceId);
        Device device = deviceRegistry.get(deviceId);
        StateSnapshot snapshot = stateConvergence.snapshot(deviceId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("device", device);
        response.put("snapshot", snapshot);
        response.put("faultMode", driver.fault(deviceId).name());
        return response;
    }

    @PostMapping("/{deviceId}/commands")
    public Map<String, Object> command(@RequestHeader(value = "X-Principal-Id", required = false)
                                       String principalId,
                                       @PathVariable String deviceId,
                                       @RequestParam(value = "wait", defaultValue = "false")
                                       boolean wait,
                                       @RequestBody Dtos.CommandRequestBody body) {
        accessControl.requireDeviceAccess(principalId, deviceId);
        Device device = deviceRegistry.get(deviceId);
        String commandId = "cmd-" + UUID.randomUUID();
        String idemKey = body.idempotencyKey() == null || body.idempotencyKey().isBlank()
                ? commandId : body.idempotencyKey();
        long timeout = body.timeoutMillis() == null ? 0L : body.timeoutMillis();
        CommandRequest request = new CommandRequest(commandId, deviceId,
                device.householdId(), body.command(),
                body.params() == null ? Map.of() : body.params(),
                idemKey, null, timeout);

        CommandService.SubmitOutcome outcome = commandService.submit(request, principalId);
        var result = outcome.result();
        if (wait && !outcome.terminal()) {
            result = commandService.awaitResult(commandId,
                    timeout > 0 ? timeout + 500 : 5000);
        }
        return toView(result);
    }

    @org.springframework.web.bind.annotation.GetMapping("/{deviceId}/commands/{commandId}")
    public Map<String, Object> commandResult(
            @RequestHeader(value = "X-Principal-Id", required = false) String principalId,
            @PathVariable String deviceId,
            @PathVariable String commandId) {
        accessControl.requireDeviceAccess(principalId, deviceId);
        var result = commandService.getResult(principalId, commandId);
        if (result == null) {
            throw new IllegalArgumentException("UNKNOWN_COMMAND: 命令不存在 " + commandId);
        }
        return toView(result);
    }

    @PostMapping("/{deviceId}/fault")
    public Map<String, Object> fault(@RequestHeader(value = "X-Principal-Id", required = false)
                                     String principalId,
                                     @PathVariable String deviceId,
                                     @RequestBody Dtos.FaultBody body) {
        accessControl.requireDeviceAccess(principalId, deviceId);
        SimulatedDeviceDriver.FaultMode mode;
        try {
            mode = SimulatedDeviceDriver.FaultMode.valueOf(body.mode());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_REQUEST: 未知故障模式 " + body.mode()
                    + "（支持 HEALTHY/OFFLINE/FAIL_ALWAYS/NO_RESPONSE）");
        }
        driver.setFault(deviceId, mode);
        return Map.of("deviceId", deviceId, "faultMode", mode.name());
    }

    private Map<String, Object> toView(com.github.h3yow.homeautomationenginejava.model.CommandResult r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("commandId", r.commandId());
        view.put("deviceId", r.deviceId());
        view.put("outcome", r.outcome());
        view.put("rejectReason", r.rejectReason());
        view.put("detail", r.detail());
        view.put("suppressReason", r.suppressReason());
        view.put("mergedIntoCommandId", r.mergedIntoCommandId());
        view.put("effectiveAttributes", r.effectiveAttributes());
        view.put("timestamp", r.timestamp());
        return view;
    }
}
