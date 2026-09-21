package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleCondition;
import com.github.h3yow.homeautomationenginejava.model.RuleTrace;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则触发、条件不满足不触发、链式触发、成环检测与追踪链路。
 */
class RuleEngineTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit();
        kit.seedTwoHomes();
        kit.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
        kit.registry.registerDevice("lamp-a1", "home-a", TestKit.lampV2(), true);
        kit.registry.registerDevice("plug-b1", "home-b", TestKit.plugV2(), true);
    }

    private Rule stateRule(String id, int priority, String device, String property,
                           RuleCondition.Operator op, Object value,
                           RuleAction... actions) {
        return new Rule(id, "home-a", id, priority, true,
                new RuleCondition.StatePredicate(device, property, op, value),
                List.of(actions));
    }

    private RuleAction setPower(String device, boolean on) {
        return new RuleAction(device, "setPower", Map.of("value", on));
    }

    @Test
    @DisplayName("条件满足触发一次：plug 断电 -> lamp 关；同一变化不重复执行")
    void triggerOncePerStateChange() {
        kit.ruleRepository.save(stateRule("r-lamp-off", 10,
                "plug-a1", "power", RuleCondition.Operator.EQ, false,
                setPower("lamp-a1", false)));

        // 设备默认 power=false；先上报一次通电（真实变化），再上报断电触发规则。
        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(2_000L);
        kit.stateService.report(new StateReport("plug-a1", 2L, null,
                Map.of("power", false)));
        kit.facade.awaitQuiet(3_000L);

        assertEquals(false, kit.stateService.snapshot("lamp-a1").property("power"));
        RuleTrace trace = kit.ruleEngine.lastTrace("home-a");
        assertTrue(trace.firedRuleIds().contains("r-lamp-off"));
        assertFalse(trace.cycle());
        log.info("[TEST] trace fired={} evaluated={} suppressed={} detail={}",
                trace.firedRuleIds(), trace.evaluatedRuleIds(),
                trace.suppressed().size(), trace.detail());

        long versionAfter = kit.stateService.snapshot("lamp-a1").version();
        // 重复/心跳式上报（无属性变化）不得再次触发
        kit.stateService.report(new StateReport("plug-a1", null, null, Map.of()));
        kit.facade.awaitQuiet(1_000L);
        assertEquals(versionAfter, kit.stateService.snapshot("lamp-a1").version(),
                "无变化上报不应再次执行动作");
    }

    @Test
    @DisplayName("条件不满足绝不触发")
    void conditionNotMetNoTrigger() {
        // 规则要求 plug 断电；上报为通电 -> 不触发，灯保持默认 false
        kit.ruleRepository.save(stateRule("r-x", 10,
                "plug-a1", "power", RuleCondition.Operator.EQ, false,
                setPower("lamp-a1", true)));
        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(1_500L);
        RuleTrace trace = kit.ruleEngine.lastTrace("home-a");
        assertTrue(trace.firedRuleIds().isEmpty());
        assertEquals(false, kit.stateService.snapshot("lamp-a1").property("power"));
        log.info("[TEST] not fired evaluated={} detail={}",
                trace.evaluatedRuleIds(), trace.detail());
    }

    @Test
    @DisplayName("链式触发：A 开 -> B 开 -> C 开，沿链各执行一次")
    void chainPropagation() {
        kit.registry.registerDevice("plug-a2", "home-a", TestKit.plugV2(), true);
        kit.ruleRepository.save(stateRule("r-ab", 10,
                "plug-a1", "power", RuleCondition.Operator.EQ, true,
                setPower("plug-a2", true)));
        // plug-a2 通电 -> lamp-a1 开
        kit.ruleRepository.save(stateRule("r-bc", 10,
                "plug-a2", "power", RuleCondition.Operator.EQ, true,
                setPower("lamp-a1", true)));

        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(3_000L);

        assertEquals(true, kit.stateService.snapshot("plug-a2").property("power"));
        assertEquals(true, kit.stateService.snapshot("lamp-a1").property("power"));
        RuleTrace trace = kit.ruleEngine.lastTrace("home-a");
        assertTrue(trace.firedRuleIds().containsAll(List.of("r-ab", "r-bc")));
        log.info("[TEST] chain fired={}", trace.firedRuleIds());
    }

    @Test
    @DisplayName("成环检测：r1->r2->r3->r4->r1 必须检测到环、终止并指出成环规则")
    void cycleDetectedAndTerminated() {
        // 无分支直接环（每跳设备不同，避免同设备同跳的冲突裁决干扰）：
        // cyc-r1 监听 c1==true  -> 令 c2=true   （c2: false->true 产生事件）
        // cyc-r2 监听 c2==true  -> 令 c3=false  （c3: 默认...先初始化为 true ->false）
        // cyc-r3 监听 c3==false -> 令 c1=false  （c1: true->false 产生事件）
        // cyc-r4 监听 c1==false -> 令 c2=false  （c2: true->false 产生事件）
        // cyc-r5 监听 c2==false -> 令 c3=true   （c3: false->true 产生事件）
        // cyc-r6 监听 c3==true  -> 令 c1=true   （c1: false->true 产生事件）
        // 此时 c1:true 再次命中 cyc-r1，路径 cyc-r1 已出现 -> 成环终止。
        String[] devs = {"plug-c1", "plug-c2", "plug-c3"};
        for (String d : devs) {
            kit.registry.registerDevice(d, "home-a", TestKit.plugV2(), true);
        }
        // c3 初始为通电，使 r2 能把它翻转为 false。
        kit.stateService.report(new StateReport("plug-c3", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(500L);

        kit.ruleRepository.save(new Rule("cyc-r1", "home-a", "c1", 10, true,
                new RuleCondition.StatePredicate("plug-c1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(setPower("plug-c2", true))));
        kit.ruleRepository.save(new Rule("cyc-r2", "home-a", "c2", 10, true,
                new RuleCondition.StatePredicate("plug-c2", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(setPower("plug-c3", false))));
        kit.ruleRepository.save(new Rule("cyc-r3", "home-a", "c3", 10, true,
                new RuleCondition.StatePredicate("plug-c3", "power",
                        RuleCondition.Operator.EQ, false),
                List.of(setPower("plug-c1", false))));
        kit.ruleRepository.save(new Rule("cyc-r4", "home-a", "c1b", 10, true,
                new RuleCondition.StatePredicate("plug-c1", "power",
                        RuleCondition.Operator.EQ, false),
                List.of(setPower("plug-c2", false))));
        kit.ruleRepository.save(new Rule("cyc-r5", "home-a", "c2b", 10, true,
                new RuleCondition.StatePredicate("plug-c2", "power",
                        RuleCondition.Operator.EQ, false),
                List.of(setPower("plug-c3", true))));
        kit.ruleRepository.save(new Rule("cyc-r6", "home-a", "c3b", 10, true,
                new RuleCondition.StatePredicate("plug-c3", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(setPower("plug-c1", true))));
        String[] rules = {"cyc-r1", "cyc-r2", "cyc-r3",
                "cyc-r4", "cyc-r5", "cyc-r6"};

        kit.stateService.report(new StateReport("plug-c1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(4_000L);

        RuleTrace trace = kit.ruleEngine.lastTrace("home-a");
        assertTrue(trace.cycle(), "必须报告成环");
        assertTrue(trace.cyclePath().size() >= 4, "cyclePath 过短: " + trace.cyclePath());
        assertEquals(trace.cyclePath().get(0), trace.cyclePath().get(trace.cyclePath().size() - 1),
                "路径首尾重复标出闭合点: " + trace.cyclePath());
        for (String d : devs) {
            long v = kit.stateService.snapshot(d).version();
            assertTrue(v <= 12L, d + " 版本异常增长（环未终止）: " + v);
        }
        log.warn("[TEST] CYCLE path={} detail={}", trace.cyclePath(), trace.detail());
    }

    @Test
    @DisplayName("时间窗口：窗口外不触发，跨午夜窗口语义正确")
    void timeWindowCondition() {
        // 22:00-06:00 跨午夜
        Rule nightRule = new Rule("r-night", "home-a", "night", 10, true,
                new RuleCondition.Composite(RuleCondition.Logic.AND, List.of(
                        new RuleCondition.StatePredicate("plug-a1", "power",
                                RuleCondition.Operator.EQ, false),
                        new RuleCondition.TimeWindow(22 * 60, 6 * 60))),
                List.of(setPower("lamp-a1", true)));
        kit.ruleRepository.save(nightRule);

        // 中午 12:00：条件组合不满足。设备默认 false，先制造一次真实变化（false 状态）。
        kit.clock.setFixed(12 * 60L * 60_000L);
        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(1_000L);
        kit.stateService.report(new StateReport("plug-a1", 2L, null,
                Map.of("power", false)));
        kit.facade.awaitQuiet(1_500L);
        assertEquals(false, kit.stateService.snapshot("lamp-a1").property("power"),
                "12:00 不在夜检窗口，即使断电也不触发");
        log.info("[TEST] noon: no trigger, lamp power={}",
                kit.stateService.snapshot("lamp-a1").property("power"));

        // 23:00 新设备：先开后关制造真实变化；落在跨午夜窗口内应触发。
        kit.clock.setFixed(23 * 60L * 60_000L);
        kit.registry.registerDevice("plug-a3", "home-a", TestKit.plugV2(), true);
        Rule nightRule2 = new Rule("r-night2", "home-a", "night2", 10, true,
                new RuleCondition.Composite(RuleCondition.Logic.AND, List.of(
                        new RuleCondition.StatePredicate("plug-a3", "power",
                                RuleCondition.Operator.EQ, false),
                        new RuleCondition.TimeWindow(22 * 60, 6 * 60))),
                List.of(setPower("lamp-a1", true)));
        kit.ruleRepository.save(nightRule2);
        kit.stateService.report(new StateReport("plug-a3", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(1_000L);
        kit.stateService.report(new StateReport("plug-a3", 2L, null,
                Map.of("power", false)));
        kit.facade.awaitQuiet(2_000L);
        assertEquals(true, kit.stateService.snapshot("lamp-a1").property("power"),
                "23:00 落在跨午夜窗口内，应触发");
        log.info("[TEST] 23:00: triggered, lamp power={}",
                kit.stateService.snapshot("lamp-a1").property("power"));
    }
}
