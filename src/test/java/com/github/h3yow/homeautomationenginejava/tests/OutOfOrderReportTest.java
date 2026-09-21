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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 乱序/重复上报测试。
 * 关键输入：对 light2-h1 以序号 10 -> 5 -> 10(重复) -> 12 乱序上报 brightness。
 * 断言：低序号/重复序号被拒绝(OUTDATED_SEQUENCE)，已生效状态不回退；
 * 未知能力属性被拒绝(UNKNOWN_CAPABILITY)；离线上报不覆盖属性；最新高序号正常收敛。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutOfOrderReportTest extends EngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderReportTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void staleAndDuplicateReportsCannotRollbackEffectiveState() throws Exception {
        String device = "light2-h1";

        JsonNode r10 = report(device, Map.of("seq", 10, "online", true,
                "attributes", Map.of("power", "on", "brightness", 100,
                        "color", "white")));
        assertTrue(r10.path("accepted").asBoolean(), "seq=10 首报应被采纳: " + r10);
        log.info("[乱序上报测试] 输入 seq=10 brightness=100 => 采纳, 最新={}",
                r10.path("latestAttributes"));

        JsonNode r5 = report(device, Map.of("seq", 5, "online", true,
                "attributes", Map.of("power", "off", "brightness", 10,
                        "color", "red")));
        assertFalse(r5.path("accepted").asBoolean());
        assertEquals("OUTDATED_SEQUENCE", r5.path("rejectReason").asText());
        log.info("[乱序上报测试] 输入 seq=5 brightness=10(回退尝试) => 拒绝原因={} 状态保持={}",
                r5.path("rejectReason").asText(), r5.path("latestAttributes"));

        JsonNode r10dup = report(device, Map.of("seq", 10, "online", true,
                "attributes", Map.of("power", "off", "brightness", 10,
                        "color", "red")));
        assertFalse(r10dup.path("accepted").asBoolean());
        assertEquals("OUTDATED_SEQUENCE", r10dup.path("rejectReason").asText());
        log.info("[乱序上报测试] 输入 seq=10 重复 => 拒绝原因={} 状态保持={}",
                r10dup.path("rejectReason").asText(), r10dup.path("latestAttributes"));

        JsonNode snapshot = getDevice(device);
        assertEquals(100, snapshot.path("snapshot").path("attributes")
                .path("brightness").asInt(), "乱序/重复上报后亮度必须仍为 100");
        assertEquals("white", snapshot.path("snapshot").path("attributes")
                .path("color").asText(), "颜色不得被乱序值覆盖");
        log.info("[乱序上报测试] 判定依据: 已生效 brightness=100/color=white 未回退");

        // 未知能力属性：明确拒绝，不静默忽略
        JsonNode unknown = report(device, Map.of("seq", 11, "online", true,
                "attributes", Map.of("power", "on", "hackerAttr", 1)));
        assertFalse(unknown.path("accepted").asBoolean());
        assertEquals("UNKNOWN_CAPABILITY", unknown.path("rejectReason").asText());
        log.info("[乱序上报测试] 输入含未知能力 hackerAttr => 拒绝原因={}",
                unknown.path("rejectReason").asText());

        // 离线上报：online=false 只改在线位，属性不被清空/覆盖
        JsonNode offline = report(device, Map.of("seq", 12, "online", false,
                "attributes", Map.of()));
        assertTrue(offline.path("accepted").asBoolean());
        assertEquals(100, offline.path("latestAttributes").path("brightness").asInt(),
                "离线不得覆盖能力属性");
        log.info("[乱序上报测试] 输入 seq=12 online=false => 采纳但属性保持 {}",
                offline.path("latestAttributes"));

        // 设备恢复在线，更高序号正常收敛
        JsonNode r20 = report(device, Map.of("seq", 20, "online", true,
                "attributes", Map.of("power", "on", "brightness", 50,
                        "color", "blue")));
        assertTrue(r20.path("accepted").asBoolean());
        assertEquals(50, r20.path("latestAttributes").path("brightness").asInt());
        log.info("[乱序上报测试] 输入 seq=20 brightness=50 => 正常收敛 {}",
                r20.path("latestAttributes"));
    }

    private JsonNode report(String deviceId, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/reports")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getDevice(String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", "alice"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
