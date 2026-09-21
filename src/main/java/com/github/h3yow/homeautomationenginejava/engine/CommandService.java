package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.Device;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 命令服务（统一命令入口）：
 * 校验身份/模型参数 -> 幂等去重 -> 入设备串行队列（风暴策略）-> 驱动执行 ->
 * 生效确认/超时/失败结论 -> 执行记录。设备首次出现时懒启动其工作线程。
 */
@Service
public class CommandService {

    public record SubmitOutcome(CommandResult result, boolean terminal) {
    }

    public SubmitOutcome submit(CommandRequest request, String principalId) {
        // TODO 骨架
        return null;
    }

    /** 规则引擎内部入口：以系统身份下发，仍受家庭范围与模型校验约束。 */
    public SubmitOutcome submitFromRule(CommandRequest request) {
        // TODO 骨架
        return null;
    }

    public CommandResult awaitResult(String commandId, long timeoutMillis) {
        // TODO 骨架
        return null;
    }

    public CommandResult getResult(String principalId, String commandId) {
        // TODO 骨架
        return null;
    }

    /** 设备注册后绑定工作线程。 */
    public void activateDevice(Device device) {
        // TODO 骨架
    }

    public Map<String, Object> validateAgainstModel(Device device, CommandRequest request) {
        // TODO 骨架
        return Map.of();
    }
}
