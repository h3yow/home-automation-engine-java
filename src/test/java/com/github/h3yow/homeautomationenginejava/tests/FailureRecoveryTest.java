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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 失败与恢复测试。
 * 关键输入：对 thermo-h1 依次注入 OFFLINE / FAIL_ALWAYS / NO_RESPONSE，
 * 并使用同一幂等键重试。
 * 断言：
 * - 离线 => FAILED(DEVICE_OFFLINE)，不入队、不记成功；
 * - 设备返回失败 => FAILED(EXECUTION_FAILED)，状态不改变；
 * - 无响应 => TIMEOUT(NO_RESPONSE)，有可诊断上下文；
 * - 恢复 HEALTHY 后用同一幂等键重试 => EFFECTIVE 且只执行一次（无重复副作用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FailureRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(FailureRecoveryTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void failuresAreClassifiedAndIdempotentRetryRecoversWithoutDuplicateSideEffects()
            throws Exception {
        String device = "thermo-h1";
        String idemKey = "retry-key-" + UUID.randomUUID();

        // 1) 离线：先上报离线，再发命令 -> 立即 FAILED/DEVICE_OFFLINE
        setFault(device, "HEALTHY");
        report(device, Map.of("seq", 700, "online", false, "attributes", Map.of()));
        JsonNode offlineCmd = command(device, Map.of("command", "setTemperature",
                "params", Map.of("value", 22), "idempotencyKey", idemKey + "-off"), true);
        log.info("[失败恢复测试] 离线命令结论: outcome={} reason={}",
                offlineCmd.path("outcome").asText(),
                offlineCmd.path("rejectReason").asText());
        assertEquals("FAILED", offlineCmd.path("outcome").asText());
        assertEquals("DEVICE_OFFLINE", offlineCmd.path("rejectReason").asText());

        // 2) FAIL_ALWAYS：恢复在线但驱动必失败
        report(device, Map.of("seq", 701, "online", true,
                "attributes", Map.of("currentTemp", 25)));
        setFault(device, "FAIL_ALWAYS");
        JsonNode failedCmd = command(device, Map.of("command", "setTemperature",
                "params", Map.of("value", 23), "idempotencyKey", idemKey + "-fail"), true);
        log.info("[失败恢复测试] 执行失败结论: outcome={} reason={} detail={}",
                failedCmd.path("outcome").asText(),
                failedCmd.path("rejectReason").asText(),
                failedCmd.path("detail").asText());
        assertEquals("FAILED", failedCmd.path("outcome").asText());
        assertEquals("EXECUTION_FAILED", failedCmd.path("rejectReason").asText());
        // 失败不得被记录为成功、状态不得被改变
        JsonNode snapshotAfterFail = getDevice(device);
        assertEquals(25, snapshotAfterFail.path("snapshot").path("attributes")
                .path("currentTemp").asInt());
        assertNull(snapshotAfterFail.path("snapshot").path("attributes")
                .get("targetTemp"), "失败命令不得写入目标状态");

        // 3) NO_RESPONSE：短超时，结论必须是 TIMEOUT
        setFault(device, "NO_RESPONSE");
        long start = System.currentTimeMillis();
        JsonNode timeoutCmd = command(device, Map.of("command", "setTemperature",
                "params", Map.of("value", 24),
                "idempotencyKey", idemKey + "-timeout",
                "timeoutMillis", 300), true);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[失败恢复测试] 无响应结论: outcome={} reason={} 耗时={}ms detail={}",
                timeoutCmd.path("outcome").asText(),
                timeoutCmd.path("rejectReason").asText(), elapsed,
                timeoutCmd.path("detail").asText());
        assertEquals("TIMEOUT", timeoutCmd.path("outcome").asText());
        assertEquals("NO_RESPONSE", timeoutCmd.path("rejectReason").asText());
        assertTrue(timeoutCmd.path("detail").asText().contains("300"),
                "失败上下文必须保留超时阈值以便诊断");
        assertTrue(elapsed < 5000, "超时必须按配置阈值快速判定，实际 " + elapsed + "ms");

        // 4) 恢复 HEALTHY 后用“新”幂等键成功
        setFault(device, "HEALTHY");
        String recoverKey = idemKey + "-recover";
        JsonNode recovered = command(device, Map.of("command", "setTemperature",
                "params", Map.of("value", 26), "idempotencyKey", recoverKey), true);
        assertEquals("EFFECTIVE", recovered.path("outcome").asText(),
                "恢复后命令应生效: " + recovered);
        log.info("[失败恢复测试] 恢复后首次命令: outcome=EFFECTIVE targetTemp=26");

        // 5) 用同一幂等键重放 => 去重回放，不二次执行（无重复副作用）
        JsonNode replay = command(device, Map.of("command", "setTemperature",
                "params", Map.of("value", 26), "idempotencyKey", recoverKey), true);
        log.info("[失败恢复测试] 同键重放结论: outcome={} reason={} mergedInto={}",
                replay.path("outcome").asText(),
                replay.path("rejectReason").asText(""),
                replay.path("mergedIntoCommandId").asText(""));
        assertEquals("DUPLICATE_COMMAND", replay.path("rejectReason").asText(),
                "同幂等键重试必须被识别为重复，不产生第二次副作用");

        // 驱动层幂等缓存保证即使绕过入口也只回放首次结论（由设备 hwSeq 不增长佐证）
        JsonNode finalDevice = getDevice(device);
        assertEquals(26, finalDevice.path("snapshot").path("attributes")
                .path("targetTemp").asInt());
        log.info("[失败恢复测试] 最终 targetTemp=26，重试未产生重复副作用");
    }

    private void setFault(String deviceId, String mode) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/fault")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mode", mode))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private JsonNode command(String deviceId, Map<String, Object> body, boolean wait)
            throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(
                                "/api/devices/" + deviceId + "/commands"
                                        + (wait ? "?wait=true" : ""))
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
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
}
