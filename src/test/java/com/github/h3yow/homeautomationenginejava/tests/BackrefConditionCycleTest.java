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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 回指设备但条件已不再成立的非成环长链测试。
 *
 * 关键输入（规则均回指窗帘/灯这两个设备）：
 *  bc-open  : 窗帘 position=open  -> 灯 turnOn
 *  bc-close : 灯 power=on         -> 窗帘 close
 *  bc-lock  : 窗帘 position=closed-> 门锁 lock
 * 片段：窗帘 position=open。
 *
 * 第三层事件是“窗帘变 closed”，此时 bc-open 虽然回指同一设备、且已在路径上，
 * 但其条件（窗帘 open）在当前状态下已不再成立，故不构成成环；bc-lock 必须继续触发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackrefConditionCycleTest extends EngineTestBase {

    private static final Logger log =
            LoggerFactory.getLogger(BackrefConditionCycleTest.class);

    private static final String R_OPEN = "bc-curtain-open-light-on";
    private static final String R_CLOSE = "bc-light-on-curtain-close";
    private static final String R_LOCK = "bc-curtain-closed-lock";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RuleRegistry ruleRegistry;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void backreferencingRuleWhoseConditionNoLongerHoldsIsNotACycleAndChainCompletes()
            throws Exception {
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        registerChainRules();

        long evalTime = 1_700_000_000_000L;
        Map<String, Object> dryBody = Map.of(
                "householdId", "H1",
                "evaluationTime", evalTime,
                "fragments", List.of(Map.of(
                        "deviceId", "curtain-h1", "seq", 10, "online", true,
                        "attributes", Map.of("position", "open"))));

        // ---- 试运行：回指规则条件不再成立，不算成环，长链推完 ----
        JsonNode dry = dryRun("alice", dryBody);
        assertEquals("COMPLETED", dry.path("terminationReason").asText(),
                "非成环长链必须正常推演完成，不得被截断");
        assertTrue(dry.path("cyclePath").isMissingNode()
                        || dry.path("cyclePath").isNull(),
                "回指规则条件已不再成立，不得给出成环结论");
        assertEquals(List.of(R_OPEN, R_CLOSE, R_LOCK),
                jsonToStringList(dry.path("firedRules")),
                "回指之后的上锁规则必须继续触发，三条规则各执行一次");
        JsonNode expected = dry.path("expectedStates");
        assertEquals("on", expected.path("light-h1").path("attributes")
                .path("power").asText(), "灯最终应为亮");
        assertEquals("closed", expected.path("curtain-h1").path("attributes")
                .path("position").asText(), "窗帘最终应为关");
        assertEquals(true, expected.path("lock-h1").path("attributes")
                .path("locked").asBoolean(), "门锁必须已锁上");
        log.info("[回指非环-试运行] fired={} 预期窗帘={} 灯={} 锁={}",
                dry.path("firedRules"),
                expected.path("curtain-h1").path("attributes").path("position").asText(),
                expected.path("light-h1").path("attributes").path("power").asText(),
                expected.path("lock-h1").path("attributes").path("locked").asBoolean());

        // 相同输入（含相同判定时间）反复推演：报告完全相同
        JsonNode dryAgain = dryRun("alice", dryBody);
        assertEquals(dry.path("dryRunId").asText(), dryAgain.path("dryRunId").asText(),
                "相同输入必须得到相同推演标识");
        assertEquals(dry, dryAgain, "相同输入（含相同判定时间）必须得到完全相同的报告");
        JsonNode dryThird = dryRun("alice", dryBody);
        assertEquals(dry, dryThird, "第三次重复推演仍须得到完全相同的报告");

        // ---- 真实上报：同样跑到上锁规则，结论与试运行一致 ----
        JsonNode resp = report("curtain-h1", Map.of("seq", 100, "online", true,
                "attributes", Map.of("position", "open")));
        assertTrue(resp.path("accepted").asBoolean());

        // 规则动作经每设备工作线程执行，轮询等待三条规则各落账一次
        int open = 0;
        int close = 0;
        int lock = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && (open < 1 || close < 1 || lock < 1)) {
            JsonNode records = listRecords();
            open = countSourceRule(records, R_OPEN);
            close = countSourceRule(records, R_CLOSE);
            lock = countSourceRule(records, R_LOCK);
            if (open < 1 || close < 1 || lock < 1) {
                Thread.sleep(50);
            }
        }
        log.info("[回指非环-真实] 动作次数 open={} close={} lock={}", open, close, lock);
        assertEquals(1, open, "同一次状态变化里 R_OPEN 只能执行一次");
        assertEquals(1, close, "同一次状态变化里 R_CLOSE 只能执行一次");
        assertEquals(1, lock, "上锁规则必须真实执行一次（旧实现会漏跑这条）");

        // 真实执行不得记录成环
        JsonNode realCycle = getCycle("alice");
        assertFalse(realCycle.path("cycleDetected").asBoolean(),
                "条件不再成立的回指不是环，真实成环记录必须为空");

        // 设备最终预期状态与试运行结论对得上
        assertEquals("closed", getDevice("alice", "curtain-h1").path("snapshot")
                .path("attributes").path("position").asText(), "真实窗帘最终应为关");
        assertEquals("on", getDevice("alice", "light-h1").path("snapshot")
                .path("attributes").path("power").asText(), "真实灯最终应为亮");
        assertEquals(true, getDevice("alice", "lock-h1").path("snapshot")
                .path("attributes").path("locked").asBoolean(), "真实门锁必须已锁上");
    }

    @Test
    void trueCycleIsStillDetectedAndTerminatedInBothRealAndDryRun() throws Exception {
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        ruleRegistry.setEnabled("cycle-a-curtain-open-light-on", true);
        ruleRegistry.setEnabled("cycle-b-light-on-curtain-open", true);

        long evalTime = 1_700_000_000_000L;
        Map<String, Object> dryBody = Map.of(
                "householdId", "H1",
                "evaluationTime", evalTime,
                "fragments", List.of(Map.of(
                        "deviceId", "curtain-h1", "seq", 10, "online", true,
                        "attributes", Map.of("position", "open"))));
        JsonNode dry = dryRun("alice", dryBody);
        JsonNode cyclePath = dry.path("cyclePath");
        assertFalse(cyclePath.isMissingNode() || cyclePath.isNull(),
                "真实闭环必须输出环路径");
        assertTrue(cyclePath.isArray() && cyclePath.size() >= 3);
        assertEquals(cyclePath.get(0).asText(),
                cyclePath.get(cyclePath.size() - 1).asText(),
                "环路径必须首尾相接指出闭合规则");
        String chain = cyclePath.toString();
        assertTrue(chain.contains("cycle-a-curtain-open-light-on"));
        assertTrue(chain.contains("cycle-b-light-on-curtain-open"));
        assertEquals(List.of("cycle-a-curtain-open-light-on",
                "cycle-b-light-on-curtain-open"),
                jsonToStringList(dry.path("firedRules")),
                "环上规则各只触发一次，不因回指同一设备而无限传播");
        assertEquals("COMPLETED", dry.path("terminationReason").asText(),
                "成环是正常识别结论，不得与容量截断混淆");
        log.info("[真实成环-试运行] 环路径={}", chain);

        // 真实上报同样识别成环并快速终止
        long start = System.currentTimeMillis();
        JsonNode resp = report("curtain-h1", Map.of("seq", 200, "online", true,
                "attributes", Map.of("position", "open")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(resp.path("accepted").asBoolean());
        assertTrue(elapsed < 20000, "成环传播必须被快速终止，实际耗时 " + elapsed + "ms");

        JsonNode realCycle = getCycle("alice");
        assertTrue(realCycle.path("cycleDetected").asBoolean(),
                "真实执行必须能查询到成环结论");
        JsonNode realPath = realCycle.path("cyclePath");
        assertEquals(realPath.get(0).asText(),
                realPath.get(realPath.size() - 1).asText(),
                "真实环路径必须首尾相接");

        int countA = 0;
        int countB = 0;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && (countA < 1 || countB < 1)) {
            JsonNode records = listRecords();
            countA = countSourceRule(records, "cycle-a-curtain-open-light-on");
            countB = countSourceRule(records, "cycle-b-light-on-curtain-open");
            if (countA < 1 || countB < 1) {
                Thread.sleep(50);
            }
        }
        assertEquals(1, countA, "环上规则 A 在一次传播中只能执行一次");
        assertEquals(1, countB, "环上规则 B 在一次传播中只能执行一次");
    }

    private void registerChainRules() {
        ruleRegistry.upsert(new Rule(R_OPEN, "H1", "窗帘打开则开灯", true, 10,
                new StateCondition("curtain-h1", "position",
                        StateCondition.Operator.EQ, "open"),
                List.of(new RuleAction("light-h1", "turnOn", Map.of()))));
        ruleRegistry.upsert(new Rule(R_CLOSE, "H1", "灯亮则关窗帘", true, 10,
                new StateCondition("light-h1", "power",
                        StateCondition.Operator.EQ, "on"),
                List.of(new RuleAction("curtain-h1", "close", Map.of()))));
        ruleRegistry.upsert(new Rule(R_LOCK, "H1", "窗帘关上则上锁", true, 10,
                new StateCondition("curtain-h1", "position",
                        StateCondition.Operator.EQ, "closed"),
                List.of(new RuleAction("lock-h1", "lock", Map.of()))));
    }

    private int countSourceRule(JsonNode records, String ruleId) {
        int count = 0;
        for (JsonNode r : records) {
            if (ruleId.equals(r.path("sourceRuleId").asText(""))) {
                count++;
            }
        }
        return count;
    }

    private List<String> jsonToStringList(JsonNode array) {
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
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getCycle(String principal) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records/cycles/H1")
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getDevice(String principal, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listRecords() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records")
                        .param("householdId", "H1")
                        .param("limit", "200")
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
