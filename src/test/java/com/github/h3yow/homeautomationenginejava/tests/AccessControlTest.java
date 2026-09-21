package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleCondition;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多住户隔离：跨家庭、角色不足、系统自动化越界、记录隔离。
 */
class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit();
        kit.seedTwoHomes();
        kit.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
        kit.registry.registerDevice("plug-b1", "home-b", TestKit.plugV2(), true);
    }

    private DeviceCommand userCommand(String device, String home, Object value) {
        return new DeviceCommand("cmd-" + UUID.randomUUID(), device, home, "setPower",
                Map.of("value", value), "tester", null, kit.clock.nowMillis(), 0L);
    }

    @Test
    @DisplayName("跨家庭操作被拒绝：原因是 CROSS_HOME_ACCESS，命令不执行")
    void crossHomeRejected() {
        // dave 属于 home-b，试图操作 home-a 的设备
        CommandResult result = kit.facade.dispatch(
                kit.registry.resident("dave"),
                userCommand("plug-a1", "home-a", true));
        assertEquals(CommandOutcome.REJECTED, result.outcome());
        assertEquals(RejectReason.CROSS_HOME_ACCESS, result.rejectReason());
        assertEquals(false, kit.stateService.snapshot("plug-a1").property("power"),
                "越权命令必须完全不生效");
        log.info("[TEST] cross-home rejected detail={}", result.detail());
    }

    @Test
    @DisplayName("角色不足被拒绝：VIEWER 只可读不可写，原因与跨家庭可区分")
    void viewerCannotWrite() {
        CommandResult result = kit.facade.dispatch(
                kit.registry.resident("carol"),
                userCommand("plug-a1", "home-a", true));
        assertEquals(CommandOutcome.REJECTED, result.outcome());
        assertEquals(RejectReason.INSUFFICIENT_ROLE, result.rejectReason());
        log.info("[TEST] role denied detail={}", result.detail());

        // VIEWER 同家庭可读
        assert kit.facade.state(kit.registry.resident("carol"), "plug-a1") != null;
        // MEMBER 同家庭可写
        CommandResult ok = kit.facade.dispatch(
                kit.registry.resident("bob"),
                userCommand("plug-a1", "home-a", true));
        assertEquals(CommandOutcome.EFFECTIVE, ok.outcome());
    }

    @Test
    @DisplayName("VIEWER 不能管理规则；OWNER 可以")
    void viewerCannotManageRules() {
        Rule rule = new Rule("r-acl", "home-a", "acl", 10, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(new RuleAction("plug-a1", "setPower", Map.of("value", true))));
        assertNull(kit.facade.saveRule(kit.registry.resident("carol"), rule));
        Rule saved = kit.facade.saveRule(kit.registry.resident("alice"), rule);
        assertEquals("r-acl", saved.ruleId());
        log.info("[TEST] rule manage role enforced");
    }

    @Test
    @DisplayName("伪造命令 homeId 绕过：网关以设备绑定家庭为准，跨家庭声明被拒")
    void spoofedHomeInCommandRejected() {
        // dave 是 home-b OWNER，但直接拿网关命令并把 homeId 伪造成 home-b 设备之外的目标
        CommandResult result = kit.commandGateway.dispatch(userCommand("plug-a1", "home-b", true));
        assertEquals(CommandOutcome.REJECTED, result.outcome());
        assertEquals(RejectReason.CROSS_HOME_ACCESS, result.rejectReason());
    }

    @Test
    @DisplayName("系统自动化动作同样不得越界：home-a 规则目标 home-b 设备被拒且留痕")
    void automationCannotCrossHome() {
        Rule evil = new Rule("r-evil", "home-a", "evil cross home", 1, true,
                new RuleCondition.StatePredicate("plug-a1", "power",
                        RuleCondition.Operator.EQ, true),
                List.of(new RuleAction("plug-b1", "setPower", Map.of("value", true))));
        kit.ruleRepository.save(evil);

        kit.stateService.report(new StateReport("plug-a1", 1L, null,
                Map.of("power", true)));
        kit.facade.awaitQuiet(2_000L);

        assertEquals(false, kit.stateService.snapshot("plug-b1").property("power"),
                "内部自动化越界动作不得生效");
        List<ExecutionRecord> records = kit.auditLog.query("home-a", null, 20);
        boolean blocked = records.stream().anyMatch(r ->
                r.ruleId() != null && r.ruleId().equals("r-evil")
                        && r.rejectReason() == RejectReason.CROSS_HOME_ACCESS);
        assertTrue(blocked, "自动化越界必须留痕且原因可查: " + records);
        log.info("[TEST] automation cross-home blocked records={}",
                records.stream().filter(r -> "r-evil".equals(r.ruleId())).toList());
    }

    @Test
    @DisplayName("执行记录按家庭隔离：home-b 看不到 home-a 的记录")
    void logsIsolatedByHome() {
        kit.facade.dispatch(kit.registry.resident("alice"),
                userCommand("plug-a1", "home-a", true));
        List<ExecutionRecord> aLogs =
                kit.facade.queryLogs(kit.registry.resident("alice"), null, 50);
        List<ExecutionRecord> bLogs =
                kit.facade.queryLogs(kit.registry.resident("dave"), null, 50);
        log.info("[TEST] aLogs={}", aLogs);
        assertTrue(aLogs.stream().anyMatch(r -> r.deviceId().equals("plug-a1")),
                "home-a logs should contain plug-a1, got: " + aLogs);
        assertFalse(bLogs.stream().anyMatch(r -> r.deviceId().equals("plug-a1")));
        // 按设备过滤时，其他家庭设备的记录查询同样为空
        List<ExecutionRecord> cross =
                kit.facade.queryLogs(kit.registry.resident("dave"), "plug-a1", 50);
        assertTrue(cross.isEmpty());
        log.info("[TEST] log isolation a={} b={}", aLogs.size(), bLogs.size());
    }
}
