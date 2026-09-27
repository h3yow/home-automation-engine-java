package com.github.h3yow.homeautomationenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.homeautomationenginejava.engine.RuleRegistry;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 回指同一设备、但回指规则条件已不再满足的“非成环”长链测试。
 *
 * 规则链（片段只给出窗帘 position=open）：
 *  bc-open-light-on   : 窗帘 position=open  -> 灯 turnOn
 *  bc-light-close     : 灯 power=on         -> 窗帘 close
 *  bc-closed-lock     : 窗帘 position=closed-> 门锁 lock
 *
 * 关键：窗帘 close 后，事件再次回到窗帘设备并重新评估 bc-open-light-on，
 * 但其条件（position=open）此时已不成立，不构成成环，上锁规则必须继续执行。
 *
 * 同一组规则在试运行与真实上报下结论必须一致：
 * fired=[open-light-on, light-close, closed-lock]，
 * 预期状态 窗帘=closed、灯=on、门锁=true；无成环结论、无重复执行。
 * 另保留一条真正闭环仍被识别并终止的用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackreferenceNonCycleChainTest extends EngineTestBase {

    private static final Logger log =
            LoggerFactory.getLogger(BackreferenceNonCycleChainTest.class);

    private static final String R_OPEN = "bc-open-light-on";
    private static final String R_CLOSE = "bc-light-close";
    private static final String R_LOCK = "bc-closed-lock";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RuleRegistry ruleRegistry;
    @Autowired
    private com.github.h3yow.homeautomationenginejava.engine.EngineProperties
            engineProperties;
    @Autowired
    private com.github.h3yow.homeautomationenginejava.engine.ExecutionLog
            executionLog;

    private final ObjectMapper json = new ObjectMapper();

    private static long noonToday() {
        return ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS)
                .plusHours(12).toInstant().toEpochMilli();
    }

    private void upsertBackreferenceChain() {
        // 演示基线规则可能作用于同一批设备，统一关闭避免干扰断言
        ruleRegistry.setEnabled("rule-lock-on-light-off", false);
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        ruleRegistry.upsert(new Rule(R_OPEN, "H1", "窗帘开->开灯", true, 10,
                new StateCondition("curtain-h1", "position",
                        StateCondition.Operator.EQ, "open"),
                List.of(new RuleAction("light-h1", "turnOn", Map.of()))));
        ruleRegistry.upsert(new Rule(R_CLOSE, "H1", "灯亮->关窗帘", true, 10,
                new StateCondition("light-h1", "power",
                        StateCondition.Operator.EQ, "on"),
                List.of(new RuleAction("curtain-h1", "close", Map.of()))));
        ruleRegistry.upsert(new Rule(R_LOCK, "H1", "窗帘关->上锁", true, 10,
                new StateCondition("curtain-h1", "position",
                        StateCondition.Operator.EQ, "closed"),
                List.of(new RuleAction("lock-h1", "lock", Map.of()))));
    }

    private Map<String, Object> curtainOpenFragmentBody(long evalTime) {
        return Map.of(
                "householdId", "H1",
                "evaluationTime", evalTime,
                "fragments", List.of(Map.of(
                        "deviceId", "curtain-h1", "seq", 10, "online", true,
                        "attributes", Map.of("position", "open"))));
    }

    // ------------------------------------------------------------------
    // 1) 试运行：回指同一设备但条件不再成立 -> 非成环，后续规则完整触发
    // ------------------------------------------------------------------
    @Test
    void dryRun_backreferenceConditionGone_chainFullyPropagatesWithoutCycle()
            throws Exception {
        upsertBackreferenceChain();
        Map<String, Object> body = curtainOpenFragmentBody(noonToday());

        JsonNode report = dryRun("alice", body);

        assertEquals("COMPLETED", report.path("terminationReason").asText(),
                "非成环长链必须正常收敛，不能报告截断");
        assertTrue(report.path("cyclePath").isMissingNode()
                        || report.path("cyclePath").isNull(),
                "回指规则条件已不成立，不得给出成环结论");
        assertEquals(List.of(R_OPEN, R_CLOSE, R_LOCK),
                toStringList(report.path("firedRules")),
                "上锁规则必须继续执行；同一条规则在一次状态变化中只触发一次");

        JsonNode states = report.path("expectedStates");
        assertEquals("closed", states.path("curtain-h1").path("attributes")
                .path("position").asText(), "窗帘最终预期为关闭");
        assertEquals("on", states.path("light-h1").path("attributes")
                .path("power").asText(), "灯最终预期为亮");
        assertEquals(true, states.path("lock-h1").path("attributes")
                .path("locked").asBoolean(), "门锁最终预期为锁上");
        log.info("[试运行-非成环回指] fired={} 预期状态 窗帘=closed 灯=on 锁=true",
                report.path("firedRules"));

        // 相同输入（含相同判定时间）反复推演 -> 完全相同的报告
        JsonNode again = dryRun("alice", body);
        assertEquals(report.path("dryRunId").asText(), again.path("dryRunId").asText());
        assertEquals(report, again, "相同输入反复推演必须得到完全相同报告");
    }

    // ------------------------------------------------------------------
    // 2) 真实执行：同样输入下真实上报结论与试运行一致
    // ------------------------------------------------------------------
    @Test
    void realReport_backreferenceConditionGone_chainFullyPropagatesWithoutCycle()
            throws Exception {
        // 先建立设备基线（此刻 bc-* 链路规则尚不存在，基线操作不会产生其执行记录）
        report("curtain-h1", Map.of("seq", 100, "online", true,
                "attributes", Map.of("position", "closed")));
        command("light-h1", Map.of("command", "turnOff"), true);
        // 再启用链路规则并解锁：解锁事件不绑定任何链路条件，不触发规则
        upsertBackreferenceChain();
        command("lock-h1", Map.of("command", "unlock"), true);

        JsonNode resp = report("curtain-h1", Map.of("seq", 101, "online", true,
                "attributes", Map.of("position", "open")));
        assertTrue(resp.path("accepted").asBoolean());

        // 三条规则各恰好一条执行记录
        Map<String, Integer> counts = awaitRuleCounts(
                Map.of(R_OPEN, 1, R_CLOSE, 1, R_LOCK, 1));
        assertEquals(1, counts.get(R_OPEN), "回指规则只能执行一次");
        assertEquals(1, counts.get(R_CLOSE));
        assertEquals(1, counts.get(R_LOCK), "上锁规则在真实执行中也必须被触发");

        // 真实成环记录必须为空（不能误报成环）
        JsonNode cycle = getCycle("alice");
        assertFalse(cycle.path("cycleDetected").asBoolean(),
                "条件已不成立的回指不得写入真实成环记录");

        // 最终真实状态与试运行预期一致
        assertEquals("closed", getDevice("alice", "curtain-h1").path("snapshot")
                .path("attributes").path("position").asText());
        assertEquals("on", getDevice("alice", "light-h1").path("snapshot")
                .path("attributes").path("power").asText());
        assertEquals(true, getDevice("alice", "lock-h1").path("snapshot")
                .path("attributes").path("locked").asBoolean());
        log.info("[真实执行-非成环回指] 三次动作落账 窗帘=closed 灯=on 锁=true counts={}",
                counts);
    }

    // ------------------------------------------------------------------
    // 3) 真正闭环仍被识别：开窗帘->开灯->开窗帘，再次满足条件并闭合
    // ------------------------------------------------------------------
    @Test
    void dryRun_realCycleStillDetectedAndTerminated() throws Exception {
        ruleRegistry.setEnabled("rule-lock-on-light-off", false);
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        ruleRegistry.setEnabled("cycle-a-curtain-open-light-on", true);
        ruleRegistry.setEnabled("cycle-b-light-on-curtain-open", true);

        JsonNode report = dryRun("alice", Map.of(
                "householdId", "H1",
                "evaluationTime", noonToday(),
                "fragments", List.of(Map.of(
                        "deviceId", "curtain-h1", "seq", 10, "online", true,
                        "attributes", Map.of("position", "open")))));

        JsonNode cyclePath = report.path("cyclePath");
        assertTrue(cyclePath.isArray() && cyclePath.size() >= 3,
                "真实闭环必须给出首尾相接的环路径");
        assertEquals(cyclePath.get(0).asText(),
                cyclePath.get(cyclePath.size() - 1).asText(),
                "环路径首尾必须是同一条规则（闭合点）");
        assertEquals(List.of("cycle-a-curtain-open-light-on",
                        "cycle-b-light-on-curtain-open",
                        "cycle-a-curtain-open-light-on"),
                toStringList(cyclePath),
                "环路径须展示 A -> B -> 回到 A 的闭合规则序列");
        assertEquals(List.of("cycle-a-curtain-open-light-on",
                        "cycle-b-light-on-curtain-open"),
                toStringList(report.path("firedRules")),
                "环上规则各只触发一次，不会无限传播");
        // 闭环时传播从该事件终止：B 已把窗帘打开，A 的开灯不会再次下发
        assertEquals("open", report.path("expectedStates").path("curtain-h1")
                .path("attributes").path("position").asText());
        assertEquals("on", report.path("expectedStates").path("light-h1")
                .path("attributes").path("power").asText());
        log.info("[试运行-真环] 环路径={} 终止于再次命中A", cyclePath);
    }

    // ------------------------------------------------------------------
    // 4) 真实引擎容量截断：非成环长链到层数上限，截断原因可区分且保留部分结果
    // ------------------------------------------------------------------
    @Test
    void realReport_capacityLimit_truncationDistinctFromCycleWithPartialResults()
            throws Exception {
        upsertBackreferenceChain();
        // 先以默认上限建立基线（“窗帘已关”会在此阶段触发一次上锁）
        report("curtain-h1", Map.of("seq", 200, "online", true,
                "attributes", Map.of("position", "closed")));
        command("light-h1", Map.of("command", "turnOff"), true);

        // 把链路层数上限收紧到 2：前两条规则可触发，第三条（上锁）被截断在之后
        int originalDepth = engineProperties.getPropagationMaxDepth();
        engineProperties.setPropagationMaxDepth(2);
        try {
            // 清空基线阶段的执行记录，使统计只针对本次“窗帘打开”事件
            executionLog.clearAll();
            JsonNode resp = report("curtain-h1", Map.of("seq", 201, "online", true,
                    "attributes", Map.of("position", "open")));
            assertTrue(resp.path("accepted").asBoolean());

            // 截断保留前两条规则的部分结果
            Map<String, Integer> counts = awaitRuleCounts(
                    Map.of(R_OPEN, 1, R_CLOSE, 1));
            assertEquals(1, counts.get(R_OPEN));
            assertEquals(1, counts.get(R_CLOSE));

            // 给被截断后不会自动补跑的上锁规则一点时间，确认它确实没执行
            Thread.sleep(300);
            JsonNode records = listRecords();
            int lockCount = 0;
            for (JsonNode r : records) {
                if (R_LOCK.equals(r.path("sourceRuleId").asText(""))) {
                    lockCount++;
                }
            }
            assertEquals(0, lockCount, "被截断的后续规则不得执行");

            // 截断不等于成环：真实成环记录必须为空
            JsonNode cycle = getCycle("alice");
            assertFalse(cycle.path("cycleDetected").asBoolean(),
                    "容量截断不得误报成环");
            assertEquals("closed", getDevice("alice", "curtain-h1").path("snapshot")
                    .path("attributes").path("position").asText(), "部分结果保留");
            assertEquals("on", getDevice("alice", "light-h1").path("snapshot")
                    .path("attributes").path("power").asText(), "部分结果保留");
            log.info("[真实执行-容量截断] 前两条规则触发后按 MAX_DEPTH 截断，非成环");
        } finally {
            engineProperties.setPropagationMaxDepth(originalDepth);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private Map<String, Integer> awaitRuleCounts(Map<String, Integer> expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        Map<String, Integer> counts = new java.util.HashMap<>();
        while (System.currentTimeMillis() < deadline) {
            JsonNode records = listRecords();
            counts.clear();
            for (String ruleId : expected.keySet()) {
                counts.put(ruleId, 0);
            }
            for (JsonNode r : records) {
                String rule = r.path("sourceRuleId").asText("");
                if (counts.containsKey(rule)) {
                    counts.put(rule, counts.get(rule) + 1);
                }
            }
            boolean all = true;
            for (Map.Entry<String, Integer> e : expected.entrySet()) {
                if (counts.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return counts;
            }
            Thread.sleep(50);
        }
        return counts;
    }

    private List<String> toStringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(node.asText());
        }
        return result;
    }

    private JsonNode dryRun(String principal, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sandbox/dry-runs")
                        .header("X-Principal-Id", principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode report(String deviceId, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/devices/" + deviceId + "/reports")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode command(String deviceId, Map<String, Object> body, boolean wait)
            throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/devices/" + deviceId + "/commands" + (wait ? "?wait=true" : ""))
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getDevice(String principal, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getCycle(String principal) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records/cycles/H1")
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listRecords() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records")
                        .param("householdId", "H1").param("limit", "200")
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
