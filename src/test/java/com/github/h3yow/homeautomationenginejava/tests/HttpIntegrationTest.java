package com.github.h3yow.homeautomationenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口集成：命令五态、上报触发规则、越权拒绝、抑制原因可查，
 * 与本地 Facade 入口结论一致。使用演示数据（alice/bob/carol@home-a, dave@home-b）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    private MvcResult dispatch(String residentId, String deviceId, String command, Object value)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("residentId", residentId);
        body.put("deviceId", deviceId);
        body.put("command", command);
        body.put("params", Map.of("value", value));
        return mockMvc.perform(post("/api/ha/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    @DisplayName("HTTP 命令生效：alice 开 plug-a1 返回 EFFECTIVE")
    void commandEffectiveOverHttp() throws Exception {
        MvcResult r = dispatch("alice", "plug-a1", "setPower", true);
        JsonNode node = json.readTree(r.getResponse().getContentAsString());
        assertEquals("EFFECTIVE", node.get("outcome").asText());
        assertTrue(node.get("accepted").asBoolean());
    }

    @Test
    @DisplayName("HTTP 越权：dave(home-b) 操作 home-a 设备返回 REJECTED/CROSS_HOME_ACCESS")
    void crossHomeDeniedOverHttp() throws Exception {
        MvcResult r = dispatch("dave", "plug-a1", "setPower", true);
        JsonNode node = json.readTree(r.getResponse().getContentAsString());
        assertEquals("REJECTED", node.get("outcome").asText());
        assertEquals("CROSS_HOME_ACCESS", node.get("rejectReason").asText());
    }

    @Test
    @DisplayName("HTTP 角色：VIEWER carol 写命令被 INSUFFICIENT_ROLE 拒绝")
    void viewerWriteDeniedOverHttp() throws Exception {
        MvcResult r = dispatch("carol", "plug-a1", "setPower", true);
        JsonNode node = json.readTree(r.getResponse().getContentAsString());
        assertEquals("REJECTED", node.get("outcome").asText());
        assertEquals("INSUFFICIENT_ROLE", node.get("rejectReason").asText());
    }

    @Test
    @DisplayName("HTTP 上报：设备乱序旧版本被 STALE_REPORT 拒绝")
    void staleReportOverHttp() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", "plug-a1");
        body.put("version", 100);
        body.put("properties", Map.of("power", true));
        mockMvc.perform(post("/api/ha/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        Map<String, Object> old = new LinkedHashMap<>(body);
        old.put("version", 99);
        MvcResult r = mockMvc.perform(post("/api/ha/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(old)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(r.getResponse().getContentAsString());
        assertEquals(false, node.get("accepted").asBoolean());
        assertEquals("STALE_REPORT", node.get("rejectReason").asText());
    }

    @Test
    @DisplayName("HTTP 上报触发规则后可查询 trace；跨家庭读状态被拒")
    void reportTriggersRuleAndTraceQueryable() throws Exception {
        // 演示规则 rule-lamp-off：plug-a1 断电 -> lamp-a1 关。
        // 先把 plug-a1 打开（真实变化），再上报关闭，触发规则（动作目标与默认相同也留痕）。
        dispatch("alice", "plug-a1", "setPower", true);
        Map<String, Object> off = Map.of(
                "deviceId", "plug-a1", "version", 200,
                "properties", Map.of("power", false));
        mockMvc.perform(post("/api/ha/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(off)))
                .andExpect(status().isOk());

        // 等待传播完成后查询 trace
        Thread.sleep(500L);
        MvcResult trace = mockMvc.perform(get("/api/ha/trace").param("residentId", "alice"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(trace.getResponse().getContentAsString());
        assertTrue(node.has("firedRuleIds"), "trace 必须包含规则触发链路");

        // carol 同家庭可读状态；dave 跨家庭被拒 403
        mockMvc.perform(get("/api/ha/devices/plug-a1/state")
                        .param("residentId", "carol"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ha/devices/plug-a1/state")
                        .param("residentId", "dave"))
                .andExpect(status().isForbidden());
    }
}
