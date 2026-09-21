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
 * 冲突裁决测试。
 * 关键输入：同一事件（thermo-h1 高温）下两条规则都要操作 light-h1.power：
 *  - rule-conflict-high  priority=100 -> turnOn
 *  - rule-conflict-low   priority=50  -> turnOff
 * 另加一条与高优先级参数完全一致的重复规则 priority=10（应合并去重）。
 * 断言：胜者唯一确定（与遍历顺序无关），灯最终为 on；低优先级动作被抑制且可查询到原因；
 * 重复动作不产生第二条命令。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConflictResolutionTest extends EngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void conflictingCommandsAreResolvedDeterministicallyAndSuppressionIsExplainable()
            throws Exception {
        upsertRule("rule-conflict-high", 100, "turnOn");
        upsertRule("rule-conflict-low", 50, "turnOff");
        upsertRule("rule-conflict-dup", 10, "turnOn");
        log.info("[冲突裁决测试] 关键输入: 高温事件下 high(100)->turnOn, low(50)->turnOff, dup(10)->turnOn(重复)");

        // 触发事件：温度升高到 30
        JsonNode resp = report("thermo-h1", Map.of("seq", 300, "online", true,
                "attributes", Map.of("currentTemp", 30)));
        assertTrue(resp.path("accepted").asBoolean());

        // 等待串行工作线程完成规则动作
        Thread.sleep(500);

        JsonNode light = getDevice("light-h1");
        String power = light.path("snapshot").path("attributes").path("power").asText();
        log.info("[冲突裁决测试] 判定结果: light-h1.power={}（高优先级 turnOn 胜出）", power);
        assertEquals("on", power, "冲突必须按 priority 降序裁决为高优先级 turnOn");

        // 被抑制规则可查询到具体原因
        JsonNode suppressedLow = getSuppression("rule-conflict-low");
        assertTrue(suppressedLow.path("suppressed").asBoolean());
        String reason = suppressedLow.path("reason").asText();
        log.info("[冲突裁决测试] 低优先级抑制原因: {}", reason);
        assertTrue(reason.contains("rule-conflict-high"), "原因必须指出胜出规则");
        assertTrue(reason.contains("CONFLICT"), "原因必须标明为冲突");

        JsonNode suppressedDup = getSuppression("rule-conflict-dup");
        log.info("[冲突裁决测试] 重复动作处理: suppressed={} reason={}",
                suppressedDup.path("suppressed").asBoolean(),
                suppressedDup.path("reason").asText(""));
        assertTrue(suppressedDup.path("suppressed").asBoolean(),
                "同参数重复动作必须被合并抑制");
        assertTrue(suppressedDup.path("reason").asText().contains("DUPLICATE"));

        // 执行记录中灯命令只应有一条 turnOn（high 规则），turnOff 不得执行
        JsonNode records = listRecords();
        int turnOn = 0;
        int turnOff = 0;
        for (JsonNode r : records) {
            if (!"light-h1".equals(r.path("deviceId").asText())) {
                continue;
            }
            // 通过记录的 sourceRuleId 与 detail 关联；统计本轮冲突规则的记录
            String src = r.path("sourceRuleId").asText("");
            if (src.equals("rule-conflict-high") || src.equals("rule-conflict-dup")) {
                turnOn++;
            }
            if (src.equals("rule-conflict-low")) {
                turnOff++;
            }
        }
        log.info("[冲突裁决测试] 执行记录: high/dup 生效数={}, low 生效数={}", turnOn, turnOff);
        assertEquals(1, turnOn, "重复动作合并后只能有一条 turnOn 命令");
        assertEquals(0, turnOff, "被抑制的 turnOff 绝不允许执行");

        // 清理：删除冲突规则，避免影响其他测试
        deleteRule("rule-conflict-high");
        deleteRule("rule-conflict-low");
        deleteRule("rule-conflict-dup");
    }

    private void upsertRule(String ruleId, int priority, String command) throws Exception {
        Map<String, Object> body = Map.of(
                "householdId", "H1",
                "name", ruleId,
                "enabled", true,
                "priority", priority,
                "condition", Map.of("type", "state",
                        "device", "thermo-h1",
                        "attr", "currentTemp",
                        "op", "GT",
                        "value", 28),
                "actions", List.of(Map.of("deviceId", "light-h1",
                        "command", command)));
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

    private JsonNode getDevice(String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getSuppression(String ruleId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(
                                "/api/records/suppressions/" + ruleId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listRecords() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(
                                "/api/records?householdId=H1&limit=300")
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void deleteRule(String ruleId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.delete("/api/rules/" + ruleId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
    }
}
