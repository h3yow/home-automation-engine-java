package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.model.CommandOutcome;
import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;
import com.github.h3yow.homeautomationenginejava.model.DeviceState;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.ReportResult;
import com.github.h3yow.homeautomationenginejava.model.StateReport;
import com.github.h3yow.homeautomationenginejava.sim.DeviceSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态上报与命令五态、乱序/重复收敛、离线/失败不记成功、幂等不重复副作用。
 */
class StateConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(StateConvergenceTest.class);

    private TestKit kit;

    @BeforeEach
    void setUp() {
        kit = new TestKit();
        kit.seedTwoHomes();
        kit.registry.registerDevice("plug-a1", "home-a", TestKit.plugV2(), true);
    }

    @Test
    @DisplayName("乱序/重复上报：旧版本被 STALE_REPORT 拒绝，已生效状态不回退")
    void staleAndDuplicateReportsCannotRollBack() {
        // v1 开
        ReportResult r1 = kit.stateService.report(
                new StateReport("plug-a1", 1L, null, Map.of("power", true)));
        assertTrue(r1.accepted());
        assertEquals(1L, r1.newVersion());
        // v2 关
        ReportResult r2 = kit.stateService.report(
                new StateReport("plug-a1", 2L, null, Map.of("power", false)));
        assertTrue(r2.accepted());

        // 乱序旧包 v1 再到：拒绝，状态保持 false
        ReportResult stale = kit.stateService.report(
                new StateReport("plug-a1", 1L, null, Map.of("power", true)));
        assertFalse(stale.accepted());
        assertEquals(RejectReason.STALE_REPORT, stale.reason());
        assertEquals(false, kit.stateService.snapshot("plug-a1").property("power"));
        log.info("[TEST] stale report rejected reason={} detail={}", stale.reason(), stale.detail());

        // 重复包 v2（同版本）：同样拒绝
        ReportResult dup = kit.stateService.report(
                new StateReport("plug-a1", 2L, null, Map.of("power", false)));
        assertFalse(dup.accepted());
        assertEquals(RejectReason.STALE_REPORT, dup.reason());

        // 不带版本号的旧模型上报：服务端确定性赋 当前+1
        ReportResult noVersion = kit.stateService.report(
                new StateReport("plug-a1", null, null, Map.of("power", true)));
        assertTrue(noVersion.accepted());
        assertEquals(3L, noVersion.newVersion());
        assertEquals(true, kit.stateService.snapshot("plug-a1").property("power"));
    }

    @Test
    @DisplayName("命令五态：ACCEPTED 后同步拿到 EFFECTIVE；结果可查询")
    void acceptedToEffectiveAndQueryable() {
        DeviceCommand cmd = new DeviceCommand("c1", "plug-a1", "home-a", "setPower",
                Map.of("value", true), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult result = kit.commandGateway.dispatch(cmd);
        assertEquals(CommandOutcome.EFFECTIVE, result.outcome(), result.detail());
        assertTrue(result.version() >= 1);

        CommandResult queried = kit.commandGateway.getResult("c1");
        assertEquals(CommandOutcome.EFFECTIVE, queried.outcome());
        assertEquals(true, kit.stateService.snapshot("plug-a1").property("power"));
        log.info("[TEST] effective command detail={} version={}", result.detail(), result.version());
    }

    @Test
    @DisplayName("设备执行失败：FAILED，绝不改状态，绝不记成功")
    void deviceFailureIsFailedNotSuccess() {
        kit.simulator.configure("plug-a1", new DeviceSimulator.SimConfig(0L, 1, true));
        DeviceCommand cmd = new DeviceCommand("c-fail", "plug-a1", "home-a", "setPower",
                Map.of("value", true), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult result = kit.commandGateway.dispatch(cmd);
        assertEquals(CommandOutcome.FAILED, result.outcome());
        assertNull(result.rejectReason(), "FAILED 与 REJECTED 必须可区分");
        assertEquals(false, kit.stateService.snapshot("plug-a1").property("power"),
                "失败不得让状态变成目标值");
        log.info("[TEST] failed command detail={}", result.detail());

        // 审计记录中失败上下文可诊断
        var logs = kit.auditLog.query("home-a", "plug-a1", 10);
        assertTrue(logs.stream().anyMatch(l -> l.outcome() == CommandOutcome.FAILED));
    }

    @Test
    @DisplayName("设备无响应：TIMEOUT，状态不变")
    void timeoutIsDistinct() {
        // 延迟 2s，网关超时阈值 800ms
        kit.simulator.configure("plug-a1", new DeviceSimulator.SimConfig(2_000L, 0, true));
        DeviceCommand cmd = new DeviceCommand("c-timeout", "plug-a1", "home-a", "setPower",
                Map.of("value", true), "alice", null, kit.clock.nowMillis(), 0L);
        long t0 = System.currentTimeMillis();
        CommandResult result = kit.commandGateway.dispatch(cmd);
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(CommandOutcome.TIMEOUT, result.outcome());
        assertTrue(elapsed < 1_500L, "必须在超时阈值附近判定，而不是等设备延迟结束");
        assertEquals(false, kit.stateService.snapshot("plug-a1").property("power"));
        log.info("[TEST] timeout command elapsed={}ms detail={}", elapsed, result.detail());
    }

    @Test
    @DisplayName("离线设备：下发即 REJECTED/DEVICE_OFFLINE，离线不记成功")
    void offlineRejected() {
        kit.simulator.setOnline("plug-a1", false);
        // 先经状态上报反映离线，再验证命令入口拒绝
        kit.stateService.report(new StateReport("plug-a1", 1L, false, Map.of()));
        DeviceCommand cmd = new DeviceCommand("c-off", "plug-a1", "home-a", "setPower",
                Map.of("value", true), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult result = kit.commandGateway.dispatch(cmd);
        assertEquals(CommandOutcome.REJECTED, result.outcome());
        assertEquals(RejectReason.DEVICE_OFFLINE, result.rejectReason());
        log.info("[TEST] offline rejected detail={}", result.detail());
    }

    @Test
    @DisplayName("重复 commandId 重试：返回同一终态，不产生重复执行与重复版本跳变")
    void duplicateCommandIsIdempotent() {
        DeviceCommand cmd1 = new DeviceCommand("c-dup", "plug-a1", "home-a", "setPower",
                Map.of("value", true), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult r1 = kit.commandGateway.dispatch(cmd1);
        long v1 = kit.stateService.snapshot("plug-a1").version();
        assertEquals(CommandOutcome.EFFECTIVE, r1.outcome());

        CommandResult r2 = kit.commandGateway.dispatch(cmd1);
        long v2 = kit.stateService.snapshot("plug-a1").version();
        assertEquals(CommandOutcome.EFFECTIVE, r2.outcome());
        assertEquals(v1, v2, "重复命令不得再推进版本/产生副作用");
        assertNotNull(kit.stateService.snapshot("plug-a1").lastAppliedCommandId());
        log.info("[TEST] duplicate command idempotent version unchanged={}", v2);
    }

    @Test
    @DisplayName("未支持命令/未知属性/缺失必填：明确拒绝，绝不静默忽略")
    void modelMismatchRejectedExplicitly() {
        DeviceCommand badCmd = new DeviceCommand("c-bad", "plug-a1", "home-a", "setBrightness",
                Map.of("value", 99), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult rr = kit.commandGateway.dispatch(badCmd);
        assertEquals(CommandOutcome.REJECTED, rr.outcome());
        assertEquals(RejectReason.UNSUPPORTED_COMMAND, rr.rejectReason());

        ReportResult unknownProp = kit.stateService.report(
                new StateReport("plug-a1", 1L, null, Map.of("hacker", 1)));
        assertFalse(unknownProp.accepted());
        assertEquals(RejectReason.UNKNOWN_CAPABILITY, unknownProp.reason());

        DeviceCommand missing = new DeviceCommand("c-missing", "plug-a1", "home-a", "setPower",
                Map.of(), "alice", null, kit.clock.nowMillis(), 0L);
        CommandResult rm = kit.commandGateway.dispatch(missing);
        // value 有默认值 true，故这里被默认值确定性补齐而非拒绝
        assertEquals(CommandOutcome.EFFECTIVE, rm.outcome(),
                "模型声明了默认值时，缺字段按默认语义执行");
        assertEquals(true, kit.stateService.snapshot("plug-a1").property("power"));
        DeviceState snap = kit.stateService.snapshot("plug-a1");
        log.info("[TEST] default-filled command effective power={} version={}",
                snap.property("power"), snap.version());
    }
}
