package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import org.springframework.stereotype.Component;

import java.util.Map;

import java.util.List;

/**
 * 测试隔离支持：在每个测试方法前把可变引擎状态恢复到“演示数据基线”，
 * 消除上一个测试残留的排队命令、故障注入、执行记录、状态快照与自定义规则。
 */
@Component
public class TestIsolationSupport {

    private final DeviceRegistry deviceRegistry;
    private final RuleRegistry ruleRegistry;
    private final StateConvergence stateConvergence;
    private final SimulatedDeviceDriver driver;
    private final PerDeviceCommandQueue queue;
    private final ExecutionLog executionLog;
    private final ConflictResolver conflictResolver;
    private final RuleEngine ruleEngine;
    private final CommandService commandService;

    public TestIsolationSupport(DeviceRegistry deviceRegistry, RuleRegistry ruleRegistry,
                                StateConvergence stateConvergence,
                                SimulatedDeviceDriver driver,
                                PerDeviceCommandQueue queue, ExecutionLog executionLog,
                                ConflictResolver conflictResolver, RuleEngine ruleEngine,
                                CommandService commandService) {
        this.deviceRegistry = deviceRegistry;
        this.ruleRegistry = ruleRegistry;
        this.stateConvergence = stateConvergence;
        this.driver = driver;
        this.queue = queue;
        this.executionLog = executionLog;
        this.conflictResolver = conflictResolver;
        this.ruleEngine = ruleEngine;
        this.commandService = commandService;
    }

    public synchronized void resetToBaseline() {
        List<Device> devices = java.util.stream.Stream.concat(
                        deviceRegistry.listByHousehold("H1").stream(),
                        deviceRegistry.listByHousehold("H2").stream())
                .toList();

        // 1) 恢复设备健康、清空残留排队命令，避免排队命令在后续重置中继续产生记录
        for (Device device : devices) {
            driver.setFault(device.deviceId(), SimulatedDeviceDriver.FaultMode.HEALTHY);
            queue.clearPending(device.deviceId());
        }

        // 2) 删除所有测试自定义规则（非演示规则），演示规则恢复默认启停
        for (String ruleId : List.copyOf(ruleRegistry.allRuleIds())) {
            if (!isDemoRule(ruleId)) {
                ruleRegistry.remove(ruleId);
            }
        }
        ruleRegistry.setEnabled("cycle-a-curtain-open-light-on", false);
        ruleRegistry.setEnabled("cycle-b-light-on-curtain-open", false);
        ruleRegistry.setEnabled("rule-light-on-when-curtain-closed", false);
        ruleRegistry.setEnabled("rule-light-off-when-cold", false);
        ruleRegistry.setEnabled("rule-lock-on-light-off", true);
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", true);
        ruleRegistry.setEnabled("rule-h2-demo", true);

        // 3) 等待工作线程排空“正在执行”的最后一条健康命令（队列已清空）
        try {
            Thread.sleep(350);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4) 排空后清空全部运行时痕迹
        for (Device device : devices) {
            driver.clearIdempotency(device.deviceId());
        }
        executionLog.clearAll();
        conflictResolver.clearSuppressions();
        ruleEngine.clearCycleHistory();
        commandService.resetRuntimeState();
        for (Device device : devices) {
            stateConvergence.resetDevice(device.deviceId());
            // 重建在线空属性基线快照（不带属性，仅建立 online=true，不触发任何规则）
            stateConvergence.report(device, 1L, true, Map.of());
        }
    }

    private boolean isDemoRule(String ruleId) {
        return ruleId.equals("rule-lock-on-light-off")
                || ruleId.equals("rule-close-curtain-hot-day")
                || ruleId.equals("rule-light-on-when-curtain-closed")
                || ruleId.equals("rule-light-off-when-cold")
                || ruleId.equals("cycle-a-curtain-open-light-on")
                || ruleId.equals("cycle-b-light-on-curtain-open")
                || ruleId.equals("rule-h2-demo");
    }
}
