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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则成环测试。
 * 关键输入：启用两条互相触发的规则
 *  cycle-a: 窗帘 open -> 灯 turnOn
 *  cycle-b: 灯 power=on -> 窗帘 open
 * 触发：上报窗帘 position=open。
 * 断言：传播在检测到环后终止，环路径同时包含两条规则并指出闭合点；
 * 动作只执行有限次（A、B 各一次），不会无限触发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleCycleTest {

    private static final Logger log = LoggerFactory.getLogger(RuleCycleTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cyclicRuleChainIsDetectedAndTerminated() throws Exception {
        // 初始状态：窗帘关闭、灯关闭，避免预置状态干扰
        report("curtain-h1", Map.of("seq", 100, "online", true,
                "attributes", Map.of("position", "closed")));
        command("light-h1", Map.of("command", "turnOff"), true);

        enableRule("cycle-a-curtain-open-light-on", true);
        enableRule("cycle-b-light-on-curtain-open", true);
        log.info("[规则成环测试] 已启用成环规则对 A(窗帘开->灯开) B(灯开->窗帘开)");

        long start = System.currentTimeMillis();
        JsonNode resp = report("curtain-h1", Map.of("seq", 101, "online", true,
                "attributes", Map.of("position", "open")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(resp.path("accepted").asBoolean());

        // 传播同步完成；若没有环检测会无限循环直到超时/栈溢出
        assertTrue(elapsed < 20000, "成环传播必须被快速终止，实际耗时 " + elapsed + "ms");
        log.info("[规则成环测试] 传播在 {}ms 内终止（环检测生效）", elapsed);

        // 检查执行记录：A、B 各只产生一次动作（一次传播去重）
        JsonNode records = listRecords();
        int countA = 0;
        int countB = 0;
        StringBuilder cycleInfo = new StringBuilder();
        for (JsonNode r : records) {
            String rule = r.path("sourceRuleId").asText("");
            if (rule.equals("cycle-a-curtain-open-light-on")) {
                countA++;
            }
            if (rule.equals("cycle-b-light-on-curtain-open")) {
                countB++;
            }
            if (r.path("traceChain").toString().contains("cycle")) {
                cycleInfo.append(r.path("traceChain"));
            }
        }
        log.info("[规则成环测试] 判定依据: 规则A动作次数={} 规则B动作次数={}", countA, countB);
        assertEquals(1, countA, "环上规则 A 在一次传播中只能执行一次");
        assertEquals(1, countB, "环上规则 B 在一次传播中只能执行一次");

        // 环路径在服务端日志与被抑制记录中体现；这里断言两条规则都在链路里且无后续无限动作
        log.info("[规则成环测试] 规则追踪链路: 根事件窗帘open -> A开灯 -> B开窗帘 -> 再次命中A => 检测成环并终止");

        // 经 API 查询成环路径：必须明确指出成环的规则与闭合点
        JsonNode cycle = getCycle();
        assertTrue(cycle.path("cycleDetected").asBoolean(), "应能查询到成环结论");
        String path = cycle.path("cyclePath").toString();
        log.info("[规则成环测试] 查询到成环路径: {}", path);
        assertTrue(path.contains("cycle-a-curtain-open-light-on"), "环路径必须包含规则A");
        assertTrue(path.contains("cycle-b-light-on-curtain-open"), "环路径必须包含规则B");
        // 闭环：路径首尾规则相同（再次命中 A）
        JsonNode cyclePath = cycle.path("cyclePath");
        assertEquals(cyclePath.get(0).asText(),
                cyclePath.get(cyclePath.size() - 1).asText(),
                "环路径必须首尾相接指出闭合规则");

        enableRule("cycle-a-curtain-open-light-on", false);
        enableRule("cycle-b-light-on-curtain-open", false);
    }

    private JsonNode getCycle() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/records/cycles/H1")
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void enableRule(String ruleId, boolean enabled) throws Exception {
        Map<String, Object> body = Map.of(
                "householdId", "H1",
                "name", ruleId,
                "enabled", enabled,
                "priority", 10,
                "condition", Map.of("type", "state",
                        "device", ruleId.startsWith("cycle-a") ? "curtain-h1" : "light-h1",
                        "attr", ruleId.startsWith("cycle-a") ? "position" : "power",
                        "op", "EQ",
                        "value", ruleId.startsWith("cycle-a") ? "open" : "on"),
                "actions", List.of(Map.of(
                        "deviceId", ruleId.startsWith("cycle-a") ? "light-h1" : "curtain-h1",
                        "command", ruleId.startsWith("cycle-a") ? "turnOn" : "open")));
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.put("/api/rules/" + ruleId)
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private JsonNode report(String deviceId, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/reports")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode command(String deviceId, Map<String, Object> body, boolean wait)
            throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/commands" + (wait ? "?wait=true" : ""))
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listRecords() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(
                                "/api/records?householdId=H1&limit=200")
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
