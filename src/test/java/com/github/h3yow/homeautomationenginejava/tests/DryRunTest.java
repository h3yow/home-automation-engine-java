package com.github.h3yow.homeautomationenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 试运行/复盘沙箱测试。
 * 覆盖：乱序/重复/越界片段、真实成环、含时间条件的非成环长链路、
 * 与真实命令并发的隔离与可重复性、容量上限截断、冲突/越权/离线/非法命令结论。
 * 每个用例同时断言：真实设备状态、正式执行记录、成环记录均不被推演污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DryRunTest extends EngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(DryRunTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    /** 本地时区今天某个整点的 epoch 毫秒（时间窗口条件的确定判定时间点）。 */
    private static long todayAt(int hour) {
        return LocalDate.now().atTime(hour, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void outOfOrderDuplicateAndInvalidFragmentsKeepDeterministicState() throws Exception {
        long evalTime = todayAt(12);
        List<Map<String, Object>> fragments = List.of(
                Map.of("deviceId", "light-h1", "seq", 10,
                        "attributes", Map.of("power", "on")),
                Map.of("deviceId", "light-h1", "seq", 5,
                        "attributes", Map.of("power", "off")),
                Map.of("deviceId", "light-h1", "seq", 10,
                        "attributes", Map.of("power", "off")),
                Map.of("deviceId", "light-h1",
                        "attributes", Map.of("bogusCapability", 1)),
                Map.of("deviceId", "light-h1",
                        "attributes", Map.of("brightness", 50)),
                Map.of("deviceId", "light2-h1", "online", false),
                Map.of("deviceId", "light-h2",
                        "attributes", Map.of("power", "on")),
                Map.of("deviceId", "no-such-device",
                        "attributes", Map.of("power", "on")));
        log.info("[试运行-乱序片段] 关键输入: 8 条片段（乱序seq/重复seq/未知能力/缺必填/离线/跨家庭/未知设备）");

        JsonNode report = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", evalTime,
                "fragments", fragments));
        log.info("[试运行-乱序片段] 报告: {}", report);

        JsonNode outcomes = report.path("fragmentOutcomes");
        assertEquals(8, outcomes.size());
        assertTrue(outcomes.get(0).path("accepted").asBoolean(), "首条片段应采纳");
        assertEquals("OUTDATED_SEQUENCE", outcomes.get(1).path("reason").asText(),
                "较旧序号片段必须判定为乱序");
        assertEquals("OUTDATED_SEQUENCE", outcomes.get(2).path("reason").asText(),
                "重复序号片段必须判定为重复");
        assertEquals("UNKNOWN_CAPABILITY", outcomes.get(3).path("reason").asText(),
                "能力范围外内容必须明确拒绝，不静默忽略");
        assertEquals("MISSING_REQUIRED_FIELD", outcomes.get(4).path("reason").asText());
        assertTrue(outcomes.get(5).path("accepted").asBoolean(), "离线片段应采纳（仅翻转在线位）");
        assertEquals("HOUSEHOLD_MISMATCH", outcomes.get(6).path("reason").asText(),
                "跨家庭片段必须拒绝");
        assertEquals("UNKNOWN_DEVICE", outcomes.get(7).path("reason").asText());

        JsonNode expected = report.path("expectedStates");
        assertEquals("on", expected.path("light-h1").path("attributes")
                        .path("power").asText(),
                "乱序/重复片段不得把预期状态改回旧值");
        assertFalse(expected.path("light2-h1").path("online").asBoolean(),
                "离线片段应翻转在线位");
        assertTrue(expected.path("light2-h1").path("attributes").isEmpty(),
                "离线片段不得覆盖能力属性");
        assertFalse(expected.has("light-h2"), "预期状态只覆盖本家庭设备");
        assertEquals("COMPLETED", report.path("termination").asText());

        // 相同输入（含相同判定时间）重复推演必须得到相同结果
        JsonNode again = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", evalTime,
                "fragments", fragments));
        assertEquals(report.toString(), again.toString(), "相同输入重复推演结果必须一致");
        assertEquals(report.path("reportId").asText(), again.path("reportId").asText());

        // 隔离：真实设备状态不被推演改变
        JsonNode realLight = getDevice("light-h1", "alice");
        assertTrue(realLight.path("snapshot").path("attributes").isEmpty(),
                "试运行不得改变真实设备状态");
    }

    @Test
    void realCycleIsDetectedInSandboxWithoutPollutingRealRecords() throws Exception {
        enableCycleRule("cycle-a-curtain-open-light-on", "curtain-h1", "position",
                "open", "light-h1", "turnOn");
        enableCycleRule("cycle-b-light-on-curtain-open", "light-h1", "power",
                "on", "curtain-h1", "open");
        log.info("[试运行-成环] 已启用成环规则对 A(窗帘开->灯开) B(灯开->窗帘开)");

        JsonNode report = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(Map.of("deviceId", "curtain-h1",
                        "attributes", Map.of("position", "open")))));
        log.info("[试运行-成环] 报告: {}", report);

        JsonNode cycles = report.path("cyclePaths");
        assertEquals(1, cycles.size(), "应检测到一条真实成环链");
        JsonNode cycle = cycles.get(0);
        assertEquals(cycle.get(0).asText(), cycle.get(cycle.size() - 1).asText(),
                "成环链必须首尾相接: " + cycle);
        assertTrue(cycle.toString().contains("cycle-a-curtain-open-light-on"));
        assertTrue(cycle.toString().contains("cycle-b-light-on-curtain-open"));

        JsonNode fired = report.path("firedRules");
        assertEquals(2, fired.size(), "环上规则各只触发一次: " + fired);
        assertEquals("open", report.path("expectedStates").path("curtain-h1")
                .path("attributes").path("position").asText());
        assertEquals("on", report.path("expectedStates").path("light-h1")
                .path("attributes").path("power").asText());

        // 隔离：正式执行记录为空、真实成环记录不被污染、真实状态不变
        assertEquals(0, listRecords("H1").size(), "试运行不得写入正式执行记录");
        JsonNode realCycle = getCycle("H1");
        assertFalse(realCycle.path("cycleDetected").asBoolean(),
                "试运行不得污染正式成环记录");
        assertTrue(getDevice("light-h1", "alice").path("snapshot")
                .path("attributes").isEmpty(), "真实灯状态不得被推演改变");
        assertTrue(getDevice("curtain-h1", "alice").path("snapshot")
                .path("attributes").isEmpty(), "真实窗帘状态不得被推演改变");
    }

    @Test
    void timeConditionedLongChainPropagatesFullyWithoutCycle() throws Exception {
        // 关闭演示规则，避免与链路动作产生冲突裁决噪音
        disableHotDayRule();
        // 三层非成环链：高温(白天窗口) -> 关窗帘 -> 开灯 -> 开卧室灯
        upsertRule("chain-1-hot-close-curtain", 10, Map.of("type", "all",
                        "children", List.of(
                                Map.of("type", "state", "device", "thermo-h1",
                                        "attr", "currentTemp", "op", "GT", "value", 28),
                                Map.of("type", "time", "start", 8 * 3600,
                                        "end", 20 * 3600))),
                List.of(Map.of("deviceId", "curtain-h1", "command", "close")));
        upsertRule("chain-2-curtain-light-on", 10,
                Map.of("type", "state", "device", "curtain-h1", "attr", "position",
                        "op", "EQ", "value", "closed"),
                List.of(Map.of("deviceId", "light-h1", "command", "turnOn")));
        upsertRule("chain-3-light-on-light2", 10,
                Map.of("type", "state", "device", "light-h1", "attr", "power",
                        "op", "EQ", "value", "on"),
                List.of(Map.of("deviceId", "light2-h1", "command", "turnOn")));

        Map<String, Object> fragment = Map.of("deviceId", "thermo-h1",
                "attributes", Map.of("currentTemp", 30));

        // 窗口内（正午）：三层链必须完整推完
        JsonNode dayReport = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(fragment)));
        log.info("[试运行-长链路] 窗口内报告: firedRules={} termination={}",
                dayReport.path("firedRules"), dayReport.path("termination"));
        assertEquals("COMPLETED", dayReport.path("termination").asText());
        assertTrue(dayReport.path("cyclePaths").isEmpty(), "非成环链不得误报成环");
        assertEquals(List.of("chain-1-hot-close-curtain", "chain-2-curtain-light-on",
                        "chain-3-light-on-light2"),
                json.convertValue(dayReport.path("firedRules"), List.class),
                "非成环长链路必须继续推完后续影响");
        JsonNode expected = dayReport.path("expectedStates");
        assertEquals("closed", expected.path("curtain-h1")
                .path("attributes").path("position").asText());
        assertEquals("on", expected.path("light-h1")
                .path("attributes").path("power").asText());
        assertEquals("on", expected.path("light2-h1")
                .path("attributes").path("power").asText());

        // 窗口外（深夜 23 点）：时间条件不满足，首条规则不得触发
        JsonNode nightReport = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(23),
                "fragments", List.of(fragment)));
        log.info("[试运行-长链路] 窗口外报告: firedRules={}",
                nightReport.path("firedRules"));
        assertTrue(nightReport.path("firedRules").isEmpty(),
                "时间条件不满足不得触发");
        assertTrue(nightReport.path("expectedStates").path("curtain-h1")
                .path("attributes").isEmpty(), "窗口外不得产生后续预期状态");
    }

    @Test
    void dryRunIsIsolatedAndRepeatableUnderConcurrentRealTraffic() throws Exception {
        Map<String, Object> dryRunBody = Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(Map.of("deviceId", "thermo-h1",
                        "attributes", Map.of("currentTemp", 30))));

        JsonNode baseline = dryRun(dryRunBody);
        JsonNode repeat = dryRun(dryRunBody);
        assertEquals(baseline.toString(), repeat.toString(), "无干扰重复推演必须一致");
        assertTrue(baseline.path("firedRules").toString()
                .contains("rule-close-curtain-hot-day"), "基线推演应命中高温关窗帘规则");
        log.info("[试运行-并发隔离] 基线报告 reportId={}",
                baseline.path("reportId").asText());

        // 并发：H2 真实命令/上报 与 H1 多份试运行同时进行
        int rounds = 6;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> realOutcomes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<JsonNode> concurrentReports = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        Thread realTraffic = new Thread(() -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发起跑超时");
                }
                for (int i = 0; i < rounds; i++) {
                    JsonNode cmd = command("light-h2", "bob",
                            Map.of("command", "turnOn",
                                    "idempotencyKey", "h2-real-" + i), true);
                    realOutcomes.add(cmd.path("outcome").asText());
                    report("thermo-h2", "bob", Map.of(
                            "attributes", Map.of("currentTemp", 20 + i)));
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "real-traffic-h2");
        Thread dryRunTraffic = new Thread(() -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发起跑超时");
                }
                for (int i = 0; i < rounds; i++) {
                    concurrentReports.add(dryRun(dryRunBody));
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "dry-run-h1");
        realTraffic.start();
        dryRunTraffic.start();
        start.countDown();
        realTraffic.join(15000);
        dryRunTraffic.join(15000);

        assertTrue(errors.isEmpty(), "并发执行不得出错: " + errors);
        assertEquals(rounds, realOutcomes.size());
        assertTrue(realOutcomes.stream().allMatch(o -> o.equals("EFFECTIVE")),
                "真实命令必须真实生效，不受试运行影响: " + realOutcomes);
        assertEquals(rounds, concurrentReports.size());
        for (JsonNode r : concurrentReports) {
            assertEquals(baseline.toString(), r.toString(),
                    "与真实流量并发时，相同输入的试运行结果必须保持一致");
        }
        log.info("[试运行-并发隔离] {} 份并发试运行报告与基线完全一致", concurrentReports.size());

        // 试运行不在真实世界留下任何痕迹
        assertEquals(0, listRecords("H1").size(), "H1 不得出现试运行产生的正式记录");
        assertTrue(getDevice("curtain-h1", "alice").path("snapshot")
                .path("attributes").isEmpty(), "真实窗帘状态不得被推演改变");
        assertFalse(getCycle("H1").path("cycleDetected").asBoolean());
        // H2 真实命令的记录真实存在（真实流量不受沙箱影响）
        assertFalse(listRecords("H2").isEmpty(), "H2 真实命令必须留下正式记录");
        assertEquals("on", getDevice("light-h2", "bob").path("snapshot")
                .path("attributes").path("power").asText());
    }

    @Test
    void capacityLimitsTruncateWithDistinguishableReason() throws Exception {
        enableCycleRule("cycle-a-curtain-open-light-on", "curtain-h1", "position",
                "open", "light-h1", "turnOn");
        enableCycleRule("cycle-b-light-on-curtain-open", "light-h1", "power",
                "on", "curtain-h1", "open");
        Map<String, Object> fragment = Map.of("deviceId", "curtain-h1",
                "attributes", Map.of("position", "open"));

        // 链路层数上限：环在深度上限处被截断（与成环终止可区分）
        JsonNode depthLimited = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(fragment),
                "limits", Map.of("maxDepth", 2)));
        log.info("[试运行-容量] 深度上限: termination={} fired={}",
                depthLimited.path("termination"), depthLimited.path("firedRules"));
        assertEquals("DEPTH_LIMIT_REACHED", depthLimited.path("termination").asText());
        assertFalse(depthLimited.path("firedRules").isEmpty(),
                "截断时必须保留已推演的部分结果");
        assertTrue(depthLimited.path("cyclePaths").isEmpty(),
                "深度截断先于成环检测，不得误报成环");

        // 访问节点上限
        JsonNode nodeLimited = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(fragment),
                "limits", Map.of("maxVisitedNodes", 3)));
        log.info("[试运行-容量] 节点上限: termination={}",
                nodeLimited.path("termination"));
        assertEquals("NODE_LIMIT_REACHED", nodeLimited.path("termination").asText());

        // 返回条目上限：报告被截断但推演正常完成
        JsonNode entryLimited = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(fragment),
                "limits", Map.of("maxReportEntries", 5)));
        log.info("[试运行-容量] 条目上限: steps={} truncated={}",
                entryLimited.path("steps").size(), entryLimited.path("stepsTruncated"));
        assertEquals(5, entryLimited.path("steps").size(), "返回条目不得越界");
        assertTrue(entryLimited.path("stepsTruncated").asBoolean(),
                "条目截断必须可区分");
        assertFalse(entryLimited.path("cyclePaths").isEmpty(),
                "条目截断不终止推演，成环结论仍应得出");

        // 相同上限配置重复执行结果一致
        JsonNode depthAgain = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(fragment),
                "limits", Map.of("maxDepth", 2)));
        assertEquals(depthLimited.toString(), depthAgain.toString());
    }

    @Test
    void conflictSuppressionIsDeterministicInSandbox() throws Exception {
        disableHotDayRule();
        // 同一事件下两条规则互斥操作 light-h1.power
        upsertRule("dry-conflict-high", 100,
                Map.of("type", "state", "device", "thermo-h1", "attr", "currentTemp",
                        "op", "GT", "value", 28),
                List.of(Map.of("deviceId", "light-h1", "command", "turnOn")));
        upsertRule("dry-conflict-low", 50,
                Map.of("type", "state", "device", "thermo-h1", "attr", "currentTemp",
                        "op", "GT", "value", 28),
                List.of(Map.of("deviceId", "light-h1", "command", "turnOff")));

        JsonNode report = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(Map.of("deviceId", "thermo-h1",
                        "attributes", Map.of("currentTemp", 30)))));
        log.info("[试运行-冲突] suppressed={} expectedPower={}",
                report.path("suppressedActions"),
                report.path("expectedStates").path("light-h1"));

        assertEquals("on", report.path("expectedStates").path("light-h1")
                        .path("attributes").path("power").asText(),
                "互斥动作必须由高优先级确定性胜出");
        String suppression = report.path("suppressedActions")
                .path("dry-conflict-low").asText();
        assertTrue(suppression.startsWith("CONFLICT"),
                "被抑制动作必须给出确定原因: " + suppression);
        assertTrue(suppression.contains("dry-conflict-high"));
        assertEquals(List.of("dry-conflict-high"),
                json.convertValue(report.path("firedRules"), List.class));
        // 正式抑制记录不被污染
        JsonNode realSuppression = getSuppression("dry-conflict-low");
        assertFalse(realSuppression.path("suppressed").asBoolean(),
                "试运行不得污染正式抑制记录");
    }

    @Test
    void scopeOfflineAndInvalidCommandConclusionsMatchRealSemantics() throws Exception {
        disableHotDayRule();
        // 跨家庭动作：H1 规则操作 H2 设备
        upsertRule("dry-cross-household", 10,
                Map.of("type", "state", "device", "thermo-h1", "attr", "currentTemp",
                        "op", "GT", "value", 28),
                List.of(Map.of("deviceId", "light-h2", "command", "turnOn")));
        // 非法命令：设备能力范围之外
        upsertRule("dry-bad-command", 10,
                Map.of("type", "state", "device", "thermo-h1", "attr", "currentTemp",
                        "op", "GT", "value", 28),
                List.of(Map.of("deviceId", "curtain-h1", "command", "explode")));

        JsonNode report = dryRun(Map.of(
                "householdId", "H1",
                "evaluationTimeMillis", todayAt(12),
                "fragments", List.of(
                        Map.of("deviceId", "lock-h1", "online", false),
                        Map.of("deviceId", "thermo-h1",
                                "attributes", Map.of("currentTemp", 30)),
                        Map.of("deviceId", "light-h1",
                                "attributes", Map.of("power", "off")))));
        log.info("[试运行-越权/离线/非法] suppressed={} steps={}",
                report.path("suppressedActions"), report.path("steps"));

        // 跨家庭动作被拒绝且原因可机读
        assertTrue(report.path("suppressedActions").path("dry-cross-household")
                .asText().contains("ACTION_TARGET_OUT_OF_SCOPE"),
                "跨家庭自动化目标必须被拒绝");
        assertFalse(report.path("expectedStates").has("light-h2"),
                "跨家庭设备不得出现在本家庭预期状态中");

        // 非法命令：与真实执行一致的 REJECTED 结论，不静默忽略
        String steps = report.path("steps").toString();
        assertTrue(steps.contains("UNKNOWN_COMMAND"),
                "能力范围外的命令必须给出确定拒绝结论: " + steps);

        // 离线设备：动作明确判定为不会生效，不假装成功
        assertTrue(steps.contains("DEVICE_OFFLINE"),
                "离线设备的动作必须给出不会生效的明确结论: " + steps);
        assertTrue(report.path("firedRules").toString()
                .contains("rule-lock-on-light-off"), "规则评估触发但动作不生效");
        JsonNode lock = report.path("expectedStates").path("lock-h1");
        assertFalse(lock.path("online").asBoolean());
        assertFalse(lock.path("attributes").has("locked"),
                "离线设备不得被假装写入生效属性");
    }

    // ---------- HTTP 辅助 ----------

    private JsonNode dryRun(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post("/api/dry-run")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void upsertRule(String ruleId, int priority, Map<String, Object> condition,
                            List<Map<String, Object>> actions) throws Exception {
        Map<String, Object> body = Map.of(
                "householdId", "H1",
                "name", ruleId,
                "enabled", true,
                "priority", priority,
                "condition", condition,
                "actions", actions);
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.put("/api/rules/" + ruleId)
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private void enableCycleRule(String ruleId, String condDevice, String condAttr,
                                 String condValue, String actionDevice, String actionCommand)
            throws Exception {
        upsertRule(ruleId, 10,
                Map.of("type", "state", "device", condDevice, "attr", condAttr,
                        "op", "EQ", "value", condValue),
                List.of(Map.of("deviceId", actionDevice, "command", actionCommand)));
    }

    /** 关闭演示规则“白天高温自动关窗帘”，避免与测试链路动作产生裁决噪音。 */
    private void disableHotDayRule() throws Exception {
        Map<String, Object> body = Map.of(
                "householdId", "H1",
                "name", "白天高温自动关窗帘",
                "enabled", false,
                "priority", 20,
                "condition", Map.of("type", "all", "children", List.of(
                        Map.of("type", "state", "device", "thermo-h1",
                                "attr", "currentTemp", "op", "GT", "value", 28),
                        Map.of("type", "not", "children", List.of(
                                Map.of("type", "time", "start", 0, "end", 21600))))),
                "actions", List.of(Map.of("deviceId", "curtain-h1",
                        "command", "close")));
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.put(
                                "/api/rules/rule-close-curtain-hot-day")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private JsonNode getDevice(String deviceId, String principal) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode command(String deviceId, String principal,
                             Map<String, Object> body, boolean wait) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post("/api/devices/" + deviceId
                                + "/commands" + (wait ? "?wait=true" : ""))
                        .header("X-Principal-Id", principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode report(String deviceId, String principal,
                            Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/reports")
                        .header("X-Principal-Id", principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<JsonNode> listRecords(String householdId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(
                                "/api/records?householdId=" + householdId + "&limit=200")
                        .header("X-Principal-Id", "carol"))
                .andReturn();
        List<JsonNode> records = new ArrayList<>();
        json.readTree(result.getResponse().getContentAsString()).forEach(records::add);
        return records;
    }

    private JsonNode getCycle(String householdId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/records/cycles/" + householdId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getSuppression(String ruleId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/records/suppressions/" + ruleId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
