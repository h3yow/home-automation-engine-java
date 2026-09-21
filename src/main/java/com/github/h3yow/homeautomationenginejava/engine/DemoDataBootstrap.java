package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.BackpressurePolicy;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.StateSnapshot;
import com.github.h3yow.homeautomationenginejava.rule.CompositeCondition;
import com.github.h3yow.homeautomationenginejava.rule.Condition;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import com.github.h3yow.homeautomationenginejava.rule.TimeWindowCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地演示数据（确定性、可重复）：
 * - 两个家庭 H1/H2，各有管理员身份；
 * - 灯/温控/窗帘/门锁四类模型（灯含 v1/v2 两个版本，v2 新增 color 能力）；
 * - 常规规则：夜间关灯联动锁门；温控高温关窗帘（组合条件）；
 * - 默认禁用的成环规则对（开窗帘->开灯->开窗帘），经 API 启用即可验证环检测；
 * - 设备注册后启动各自命令工作线程，并写入初始在线快照。
 */
@Component
@Order(0)
public class DemoDataBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataBootstrap.class);

    private final AccessControl accessControl;
    private final ModelRegistry modelRegistry;
    private final DeviceRegistry deviceRegistry;
    private final RuleRegistry ruleRegistry;
    private final StateConvergence stateConvergence;
    private final CommandService commandService;
    private final EngineClock clock;

    public DemoDataBootstrap(AccessControl accessControl, ModelRegistry modelRegistry,
                             DeviceRegistry deviceRegistry, RuleRegistry ruleRegistry,
                             StateConvergence stateConvergence, CommandService commandService,
                             EngineClock clock) {
        this.accessControl = accessControl;
        this.modelRegistry = modelRegistry;
        this.deviceRegistry = deviceRegistry;
        this.ruleRegistry = ruleRegistry;
        this.stateConvergence = stateConvergence;
        this.commandService = commandService;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        registerPrincipals();
        registerModels();
        registerDevices();
        registerRules();
        log.info("[演示数据] 初始化完成");
    }

    private void registerPrincipals() {
        accessControl.registerPrincipal(new Principal("alice", Set.of("H1"), "Alice(H1)"));
        accessControl.registerPrincipal(new Principal("bob", Set.of("H2"), "Bob(H2)"));
        // 跨家庭管理员，用于多住户正例
        accessControl.registerPrincipal(new Principal("carol", Set.of("H1", "H2"), "Carol"));
    }

    private void registerModels() {
        DeviceModel lightV1 = new DeviceModel(
                "smart-light", 1,
                Set.of("turnOn", "turnOff", "setBrightness"),
                Map.of("turnOn", Set.of(), "turnOff", Set.of(),
                        "setBrightness", Set.of("value")),
                Map.of("turnOn", "power", "turnOff", "power",
                        "setBrightness", "brightness"),
                Set.of("power", "brightness"),
                Set.of("power"),
                Map.of("setBrightness", List.of(10, 20, 50, 100)));
        DeviceModel lightV2 = new DeviceModel(
                "smart-light", 2,
                Set.of("turnOn", "turnOff", "setBrightness", "setColor"),
                Map.of("turnOn", Set.of(), "turnOff", Set.of(),
                        "setBrightness", Set.of("value"), "setColor", Set.of("value")),
                Map.of("turnOn", "power", "turnOff", "power",
                        "setBrightness", "brightness", "setColor", "color"),
                Set.of("power", "brightness", "color"),
                Set.of("power"),
                Map.of("setBrightness", List.of(10, 20, 50, 100)));
        DeviceModel thermostat = new DeviceModel(
                "thermostat", 1,
                Set.of("setTemperature"),
                Map.of("setTemperature", Set.of("value")),
                Map.of("setTemperature", "targetTemp"),
                Set.of("currentTemp", "targetTemp"),
                Set.of("currentTemp"),
                Map.of());
        DeviceModel curtain = new DeviceModel(
                "curtain", 1,
                Set.of("open", "close"),
                Map.of("open", Set.of(), "close", Set.of()),
                Map.of("open", "position", "close", "position"),
                Set.of("position"),
                Set.of("position"),
                Map.of());
        DeviceModel lock = new DeviceModel(
                "door-lock", 1,
                Set.of("lock", "unlock"),
                Map.of("lock", Set.of(), "unlock", Set.of()),
                Map.of("lock", "locked", "unlock", "locked"),
                Set.of("locked"),
                Set.of("locked"),
                Map.of());
        modelRegistry.registerModel(lightV1);
        modelRegistry.registerModel(lightV2);
        modelRegistry.registerModel(thermostat);
        modelRegistry.registerModel(curtain);
        modelRegistry.registerModel(lock);
    }

    private void registerDevices() {
        List<Device> devices = List.of(
                new Device("light-h1", "H1", "smart-light", 1, "客厅灯"),
                new Device("light2-h1", "H1", "smart-light", 2, "卧室灯v2"),
                new Device("thermo-h1", "H1", "thermostat", 1, "客厅温控"),
                new Device("curtain-h1", "H1", "curtain", 1, "客厅窗帘"),
                new Device("lock-h1", "H1", "door-lock", 1, "入户门锁"),
                new Device("light-h2", "H2", "smart-light", 2, "H2客厅灯"),
                new Device("thermo-h2", "H2", "thermostat", 1, "H2温控")
        );
        for (Device device : devices) {
            deviceRegistry.register(device);
            commandService.activateDevice(device);
            // 初始在线快照（无能力属性，避免误触发规则）
            stateConvergence.report(device, 0L, true, Map.of());
        }
    }

    private void registerRules() {
        // H1：灯关闭后自动锁门（链式规则示例）
        Condition lightOff = new StateCondition("light-h1", "power",
                StateCondition.Operator.EQ, "off");
        ruleRegistry.upsert(new Rule("rule-lock-on-light-off", "H1",
                "关灯自动锁门", true, 10, lightOff,
                List.of(new RuleAction("lock-h1", "lock", Map.of()))));

        // H1：温控电流温度 > 28 且不在夜间窗口(00:00-06:00) 时关窗帘（组合+时间窗口）
        Condition hot = new StateCondition("thermo-h1", "currentTemp",
                StateCondition.Operator.GT, 28);
        Condition notNight = new CompositeCondition(CompositeCondition.Logic.NOT,
                List.of(new TimeWindowCondition(0, 6 * 3600)));
        Condition hotDay = new CompositeCondition(CompositeCondition.Logic.ALL,
                List.of(hot, notNight));
        ruleRegistry.upsert(new Rule("rule-close-curtain-hot-day", "H1",
                "白天高温自动关窗帘", true, 20, hotDay,
                List.of(new RuleAction("curtain-h1", "close", Map.of()))));

        // H1：两条高优先级规则对同一灯给出冲突指令，用于确定性裁决演示（默认关一条）
        Condition curtainClosed = new StateCondition("curtain-h1", "position",
                StateCondition.Operator.EQ, "closed");
        ruleRegistry.upsert(new Rule("rule-light-on-when-curtain-closed", "H1",
                "关窗帘时开灯(高优先级)", false, 100, curtainClosed,
                List.of(new RuleAction("light-h1", "turnOn", Map.of()))));
        Condition tempLow = new StateCondition("thermo-h1", "currentTemp",
                StateCondition.Operator.LT, 10);
        ruleRegistry.upsert(new Rule("rule-light-off-when-cold", "H1",
                "寒冷时关灯(低优先级)", false, 50, tempLow,
                List.of(new RuleAction("light-h1", "turnOff", Map.of()))));

        // H1：默认禁用的成环规则对：开窗帘 -> 开灯 -> 开窗帘
        Condition curtainOpen = new StateCondition("curtain-h1", "position",
                StateCondition.Operator.EQ, "open");
        Condition lightOn = new StateCondition("light-h1", "power",
                StateCondition.Operator.EQ, "on");
        ruleRegistry.upsert(new Rule("cycle-a-curtain-open-light-on", "H1",
                "[环]开窗帘则开灯", false, 10, curtainOpen,
                List.of(new RuleAction("light-h1", "turnOn", Map.of()))));
        ruleRegistry.upsert(new Rule("cycle-b-light-on-curtain-open", "H1",
                "[环]开灯则开窗帘", false, 10, lightOn,
                List.of(new RuleAction("curtain-h1", "open", Map.of()))));

        // H2：独立家庭的规则，用于验证跨家庭隔离
        Condition h2LightOff = new StateCondition("light-h2", "power",
                StateCondition.Operator.EQ, "off");
        ruleRegistry.upsert(new Rule("rule-h2-demo", "H2", "H2示例规则", true,
                10, h2LightOff, List.of()));
    }
}
