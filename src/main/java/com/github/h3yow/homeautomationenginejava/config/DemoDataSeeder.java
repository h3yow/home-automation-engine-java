package com.github.h3yow.homeautomationenginejava.config;

import com.github.h3yow.homeautomationenginejava.engine.DeviceRegistry;
import com.github.h3yow.homeautomationenginejava.engine.RuleRepository;
import com.github.h3yow.homeautomationenginejava.model.DeviceModel;
import com.github.h3yow.homeautomationenginejava.model.Home;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import com.github.h3yow.homeautomationenginejava.model.Role;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地演示数据：两个家庭、各自住户、开关/灯两类模拟设备与一条自动化规则。
 * 可通过 ha.demo.seed=false 关闭。
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DeviceRegistry registry;
    private final RuleRepository ruleRepository;
    private final boolean enabled;

    public DemoDataSeeder(DeviceRegistry registry, RuleRepository ruleRepository,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${ha.demo.seed:true}") boolean enabled) {
        this.registry = registry;
        this.ruleRepository = ruleRepository;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        registry.registerHome(new Home("home-a", "A 家庭"));
        registry.registerHome(new Home("home-b", "B 家庭"));
        registry.registerResident(new Resident("alice", "home-a", Role.OWNER));
        registry.registerResident(new Resident("bob", "home-a", Role.MEMBER));
        registry.registerResident(new Resident("carol", "home-a", Role.VIEWER));
        registry.registerResident(new Resident("dave", "home-b", Role.OWNER));

        DeviceModel plugV2 = new DeviceModel("smart-plug", 2,
                Map.of("switch", Set.of("setPower")),
                Map.of("power", false),
                Map.of("value", true),
                Map.of("setPower", Set.of("value")));
        DeviceModel lampV1 = new DeviceModel("smart-lamp", 1,
                Map.of("light", Set.of("setPower", "setBrightness")),
                Map.of("power", false, "brightness", 50),
                Map.of("value", 50),
                Map.of("setPower", Set.of("value")));

        registry.registerDevice("plug-a1", "home-a", plugV2, true);
        registry.registerDevice("lamp-a1", "home-a", lampV1, true);
        registry.registerDevice("plug-b1", "home-b", plugV2, true);

        // 插座断电 -> 自动关灯（同家庭内的链式示例基础规则）。
        Rule autoLampOff = new Rule("rule-lamp-off", "home-a", "插座断电则关灯",
                10, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, false),
                List.of(new RuleAction("lamp-a1", "setPower", Map.of("value", false))));
        ruleRepository.save(autoLampOff);

        log.info("[DEMO] seeded homes/residents/devices/rules. "
                + "residents: alice(OWNER,A) bob(MEMBER,A) carol(VIEWER,A) dave(OWNER,B)");
    }
}
