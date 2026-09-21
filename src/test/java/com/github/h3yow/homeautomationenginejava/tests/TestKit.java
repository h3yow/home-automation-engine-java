package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.AuditLog;
import com.github.h3yow.homeautomationenginejava.engine.AutomationDispatcher;
import com.github.h3yow.homeautomationenginejava.engine.BackpressurePolicy;
import com.github.h3yow.homeautomationenginejava.engine.CommandGateway;
import com.github.h3yow.homeautomationenginejava.engine.ConflictResolver;
import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.EngineClock;
import com.github.h3yow.homeautomationenginejava.engine.HomeAutomationFacade;
import com.github.h3yow.homeautomationenginejava.engine.ModelValidator;
import com.github.h3yow.homeautomationenginejava.engine.RuleEngine;
import com.github.h3yow.homeautomationenginejava.engine.RuleEvaluator;
import com.github.h3yow.homeautomationenginejava.engine.RuleRepository;
import com.github.h3yow.homeautomationenginejava.engine.StateService;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.Home;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import com.github.h3yow.homeautomationenginejava.model.Role;
import com.github.h3yow.homeautomationenginejava.sim.DeviceSimulator;

import java.util.Map;
import java.util.Set;

/**
 * 纯手工装配的测试套件：不启动 Spring，组件全真实、设备全模拟，结论与生产一致。
 */
public final class TestKit {

    public final EngineClock clock;
    public final DeviceRegistry registry;
    public final ModelValidator modelValidator;
    public final AccessControl accessControl;
    public final AuditLog auditLog;
    public final BackpressurePolicy backpressure;
    public final StateService stateService;
    public final DeviceSimulator simulator;
    public final CommandGateway commandGateway;
    public final RuleRepository ruleRepository;
    public final ConflictResolver conflictResolver;
    public final RuleEvaluator ruleEvaluator;
    public final RuleEngine ruleEngine;
    public final AutomationDispatcher automationDispatcher;
    public final HomeAutomationFacade facade;

    public TestKit() {
        this(8, "REJECT");
    }

    public TestKit(int maxQueuePerDevice, String mode) {
        clock = new EngineClock();
        registry = new DeviceRegistry(clock);
        modelValidator = new ModelValidator(registry);
        accessControl = new AccessControl(registry);
        auditLog = new AuditLog();
        backpressure = new BackpressurePolicy(maxQueuePerDevice, 50L, mode);
        stateService = new StateService(registry, modelValidator, clock);
        simulator = new DeviceSimulator(registry);
        commandGateway = new CommandGateway(registry, stateService, modelValidator,
                backpressure, auditLog, clock, simulator);
        commandGateway.setCommandTimeoutMillis(800L);
        ruleRepository = new RuleRepository();
        conflictResolver = new ConflictResolver();
        ruleEvaluator = new RuleEvaluator(registry, clock);
        automationDispatcher = new AutomationDispatcher(accessControl, commandGateway,
                auditLog, clock);
        // 先创建引擎并注册为状态监听，再暴露 Facade，保证任何状态变化都能进入传播。
        ruleEngine = new RuleEngine(ruleRepository, ruleEvaluator, conflictResolver,
                stateService, automationDispatcher, commandGateway, clock);
        stateService.addListener(ruleEngine);
        facade = new HomeAutomationFacade(registry, accessControl, commandGateway,
                stateService, ruleEngine, ruleRepository, auditLog, clock);
    }

    /** 标准插头 v2：支持 setPower(value:Boolean)，默认 power=false。 */
    public static DeviceModel plugV2() {
        return new DeviceModel("smart-plug", 2,
                Map.of("switch", Set.of("setPower")),
                Map.of("power", false),
                Map.of("value", true),
                Map.of("setPower", Set.of("value")));
    }

    /** 旧版本灯模型 v1：无亮度字段，默认 power=false。 */
    public static DeviceModel lampV1() {
        return new DeviceModel("smart-lamp", 1,
                Map.of("light", Set.of("setPower")),
                Map.of("power", false),
                Map.of("value", true),
                Map.of("setPower", Set.of("value")));
    }

    /** 新版本灯模型 v2：增加 brightness，默认 50。 */
    public static DeviceModel lampV2() {
        // 命令默认值按命令名命名空间：setPower.value=Boolean，setBrightness.value=Integer。
        return new DeviceModel("smart-lamp", 2,
                Map.of("light", Set.of("setPower", "setBrightness")),
                Map.of("power", false, "brightness", 50),
                Map.of("setPower.value", true, "setBrightness.value", 50),
                Map.of("setPower", Set.of("value"),
                        "setBrightness", Set.of("value")));
    }

    public void seedTwoHomes() {
        registry.registerHome(new Home("home-a", "A"));
        registry.registerHome(new Home("home-b", "B"));
        registry.registerResident(new Resident("alice", "home-a", Role.OWNER));
        registry.registerResident(new Resident("bob", "home-a", Role.MEMBER));
        registry.registerResident(new Resident("carol", "home-a", Role.VIEWER));
        registry.registerResident(new Resident("dave", "home-b", Role.OWNER));
    }
}
