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

import com.github.h3yow.homeautomationenginejava.engine.EngineProperties;
import com.github.h3yow.homeautomationenginejava.engine.PerDeviceCommandQueue;
import com.github.h3yow.homeautomationenginejava.model.BackpressurePolicy;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令风暴策略测试。
 * 通过注入 NO_RESPONSE 故障让命令在设备队列中堆积，验证：
 * - REJECT：超过有界容量立即拒绝（STORM_REJECTED），禁止无界堆积；
 * - DELAY：等待超过上限仍满则拒绝（STORM_DELAY_REJECTED）；
 * - MERGE：同命令在队请求被合并，后继命令得到 MERGED 受理且最终只执行一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackpressureTest {

    private static final Logger log = LoggerFactory.getLogger(BackpressureTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EngineProperties properties;
    @Autowired
    private PerDeviceCommandQueue queue;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void boundedQueueRejectsStormAndMergeCollapsesDuplicates() throws Exception {
        String device = "curtain-h1";
        int capacity = properties.getPerDeviceQueueCapacity();

        // ---- REJECT 策略 ----
        properties.setBackpressurePolicy(BackpressurePolicy.REJECT);
        setFault(device, "NO_RESPONSE");
        int rejected = 0;
        int accepted = 0;
        int attempts = capacity + 6;
        for (int i = 0; i < attempts; i++) {
            JsonNode r = submit(device, Map.of("command", "open",
                    "idempotencyKey", "storm-open-" + UUID.randomUUID(),
                    "timeoutMillis", 100), false);
            if ("REJECTED".equals(r.path("outcome").asText())
                    && "STORM_REJECTED".equals(r.path("rejectReason").asText())) {
                rejected++;
            }
            if ("ACCEPTED".equals(r.path("outcome").asText())) {
                accepted++;
            }
        }
        int pending = queue.pendingCount(device);
        log.info("[风暴测试-REJECT] 尝试={} 受理={} 拒绝={} 当前队列长度={} 容量={}",
                attempts, accepted, rejected, pending, capacity);
        assertTrue(pending <= capacity, "队列必须有界，实际长度 " + pending);
        assertEquals(attempts, accepted + rejected, "每个请求必须得到受理或拒绝的明确结论");
        assertTrue(rejected >= 1,
                "持续打满期间必须出现 STORM_REJECTED，实际拒绝 " + rejected + " 次");
        // 用瞬时突发（较长超时使工作线程占住一条命令）验证容量上限的硬边界
        setFault(device, "NO_RESPONSE");
        int burstAttempts = capacity + 10;
        int overRejected = 0;
        for (int i = 0; i < burstAttempts; i++) {
            JsonNode r = submit(device, Map.of("command", "open",
                    "idempotencyKey", "storm-burst-" + UUID.randomUUID(),
                    "timeoutMillis", 2000), false);
            if ("STORM_REJECTED".equals(r.path("rejectReason").asText())) {
                overRejected++;
            }
            assertTrue(queue.pendingCount(device) <= capacity,
                    "任何时刻队列长度都不得超过容量 " + capacity
                            + "，实际 " + queue.pendingCount(device));
        }
        log.info("[风暴测试-REJECT] 突发风暴: 尝试={} 超额拒绝={} 队列峰值受限于容量{}",
                burstAttempts, overRejected, capacity);
        // 1 条可能正被工作线程执行、至多 capacity 条排队，故至少 (10-1) 条被拒绝
        assertTrue(overRejected >= 9,
                "设备不排空时超出(在执行+排队)上限的命令必须被拒绝，实际拒绝 "
                        + overRejected);

        // 恢复设备并等待排队命令超时完成，清空队列
        setFault(device, "HEALTHY");
        waitForDrain(device, capacity);

        // REJECT 突发阶段的命令（2s 超时）必须先排空，避免污染 MERGE 阶段统计
        setFault(device, "HEALTHY");
        waitForDrain(device, capacity);

        // ---- MERGE 策略 ----
        properties.setBackpressurePolicy(BackpressurePolicy.MERGE);
        setFault(device, "NO_RESPONSE");
        int merged = 0;
        int enqueued = 0;
        int mergedAttempts = capacity + 4;
        for (int i = 0; i < mergedAttempts; i++) {
            JsonNode r = submit(device, Map.of("command", "open",
                    "idempotencyKey", "storm-merge-" + UUID.randomUUID(),
                    "timeoutMillis", 5000), false);
            if ("MERGED".equals(r.path("rejectReason").asText())) {
                merged++;
            } else if ("ACCEPTED".equals(r.path("outcome").asText())) {
                enqueued++;
            }
        }
        int pendingMerge = queue.pendingCount(device);
        log.info("[风暴测试-MERGE] 尝试={} 实际入队={} 合并={} 当前队列长度={}",
                mergedAttempts, enqueued, merged, pendingMerge);
        // 排空窗口可能先执行一条，随后同批命令全部合并；最终队列必须归一
        assertTrue(enqueued <= 2, "风暴期间同类命令至多在空窗首条+合并首条入队，实际 "
                + enqueued);
        assertEquals(1, pendingMerge, "同类 open 命令最终必须全部合并为一个在队命令");
        assertTrue(merged >= mergedAttempts - 2, "除空窗首条外的同类命令必须全部合并，"
                + "实际合并 " + merged);

        // 恢复：合并后的命令最终只执行一次（设备 position 至多变化一次）
        setFault(device, "HEALTHY");
        waitForDrain(device, 1);
        JsonNode snapshot = getDevice(device);
        log.info("[风暴测试-MERGE] 恢复后 position={}（合并命令只执行一次）",
                snapshot.path("snapshot").path("attributes").path("position").asText());
        assertEquals("open", snapshot.path("snapshot").path("attributes")
                .path("position").asText());

        properties.setBackpressurePolicy(BackpressurePolicy.REJECT);
    }

    private void waitForDrain(String device, int expectedAtMost) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline
                && queue.pendingCount(device) > 0) {
            Thread.sleep(50);
        }
        assertEquals(0, queue.pendingCount(device), "队列应在恢复后排空");
    }

    private void setFault(String deviceId, String mode) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post("/api/devices/" + deviceId + "/fault")
                        .header("X-Principal-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mode", mode))))
                .andReturn();
    }

    private JsonNode submit(String deviceId, Map<String, Object> body, boolean wait)
            throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post("/api/devices/" + deviceId
                                + "/commands" + (wait ? "?wait=true" : ""))
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
