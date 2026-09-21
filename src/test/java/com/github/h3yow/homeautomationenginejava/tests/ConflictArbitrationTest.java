package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.ConflictResolver;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲突裁决：优先级、同优先级确定性、遍历顺序无关、抑制原因可查。
 */
class ConflictArbitrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConflictArbitrationTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit();
        kit.seedTwoHomes();
        kit.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
        kit.registry.registerDevice("lamp-a1", "home-a", TestKit.lampV2(), true);
    }

    private Rule rule(String id, int priority) {
        return new Rule(id, "home-a", id, priority, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of());
    }

    private ConflictResolver.Candidate candidate(String id, int priority,
                                                 String device, String command, Object value) {
        Rule r = rule(id, priority);
        RuleAction a = new RuleAction(device, command, Map.of("value", value));
        return new ConflictResolver.Candidate(r, a, 0);
    }

    @Test
    @DisplayName("同设备互斥指令：priority 小者胜，高优先级被抑制且原因含胜出者")
    void priorityWins() {
        List<ConflictResolver.Candidate> cands = List.of(
                candidate("rule-low", 20, "lamp-a1", "setPower", true),
                candidate("rule-high", 5, "lamp-a1", "setPower", false));
        ConflictResolver.ResolvedBatch batch = kit.conflictResolver.resolve(cands);

        assertEquals(1, batch.winners().size());
        assertEquals("rule-high", batch.winners().get(0).rule().ruleId());
        assertEquals(false, batch.winners().get(0).action().params().get("value"));
        assertEquals(1, batch.suppressed().size());
        RuleTrace.SuppressedAction s = batch.suppressed().get(0);
        assertEquals("rule-low", s.ruleId());
        assertEquals(RejectReason.CONFLICT_SUPPRESSED, s.reason());
        assertTrue(s.detail().contains("rule-high"), "抑制详情必须指出胜出者: " + s.detail());
        log.info("[TEST] priorityWins winner=rule-high suppressed detail={}", s.detail());
    }

    @Test
    @DisplayName("同优先级：ruleId 字典序决胜，与输入遍历顺序无关")
    void tieBreakByRuleIdOrderIndependent() {
        // 故意把字典序大的放在输入最前，结果必须仍由 zzz 与 aaa 中较小者 aaa 胜出
        List<ConflictResolver.Candidate> order1 = List.of(
                candidate("zzz-rule", 10, "lamp-a1", "setPower", true),
                candidate("mmm-rule", 10, "lamp-a1", "setPower", false),
                candidate("aaa-rule", 10, "lamp-a1", "setPower", true));
        List<ConflictResolver.Candidate> order2 = new ArrayList<>(order1);
        java.util.Collections.reverse(order2);

        ConflictResolver.ResolvedBatch b1 = kit.conflictResolver.resolve(order1);
        ConflictResolver.ResolvedBatch b2 = kit.conflictResolver.resolve(order2);

        assertEquals("aaa-rule", b1.winners().get(0).rule().ruleId());
        assertEquals("aaa-rule", b2.winners().get(0).rule().ruleId());
        assertEquals(2, b1.suppressed().size());
        assertEquals(2, b2.suppressed().size());
        // 重复 20 次打乱输入，结论必须恒定
        List<ConflictResolver.Candidate> mutable = new ArrayList<>(order1);
        for (int round = 0; round < 20; round++) {
            java.util.Collections.shuffle(mutable, new java.util.Random(round));
            ConflictResolver.ResolvedBatch bx = kit.conflictResolver.resolve(mutable);
            assertEquals("aaa-rule", bx.winners().get(0).rule().ruleId(),
                    "第 " + round + " 轮裁决随遍历顺序漂移");
        }
        log.info("[TEST] tie-break deterministic across 20 shuffles");
    }

    @Test
    @DisplayName("不同设备互不冲突：各设备指令均保留，不误伤")
    void differentDevicesDoNotConflict() {
        List<ConflictResolver.Candidate> cands = List.of(
                candidate("r1", 5, "lamp-a1", "setPower", false),
                candidate("r2", 9, "plug-a1", "setPower", true));
        ConflictResolver.ResolvedBatch batch = kit.conflictResolver.resolve(cands);
        assertEquals(2, batch.winners().size());
        assertTrue(batch.suppressed().isEmpty());
    }

    @Test
    @DisplayName("端到端：两条规则同事件对同一灯下发互斥指令，只有高优先级生效，抑制可在 trace 查询")
    void endToEndConflictQueryable() {
        Rule high = new Rule("high-off", "home-a", "high", 5, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(new RuleAction("lamp-a1", "setPower", Map.of("value", false))));
        Rule low = new Rule("low-on", "home-a", "low", 9, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(new RuleAction("lamp-a1", "setPower", Map.of("value", true))));
        kit.ruleRepository.save(high);
        kit.ruleRepository.save(low);

        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(3_000L);

        // 灯默认 false：高优先级下发 false 与默认相同可能无状态变化；
        // 关键断言在 trace：低优先级动作必须被抑制并给出原因。
        RuleTrace trace = kit.ruleEngine.lastTrace("home-a");
        assertTrue(trace.firedRuleIds().contains("high-off"));
        assertFalse(trace.firedRuleIds().contains("low-on"));
        assertTrue(trace.suppressed().stream().anyMatch(s ->
                s.ruleId().equals("low-on")
                        && s.reason() == RejectReason.CONFLICT_SUPPRESSED
                        && s.detail().contains("high-off")),
                "被抑制动作必须可查到具体原因与胜出者");
        log.info("[TEST] suppressed in trace: {}", trace.suppressed());
    }
}
