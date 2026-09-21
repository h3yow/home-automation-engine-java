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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发入口测试：多个用户入口同时对同一设备下发命令。
 * 关键输入：8 个并发 setBrightness(各带独立幂等键) + 2 个重复幂等键命令。
 * 断言：同设备命令串行、最终状态确定、重复命令去重不二次执行、全部非成功结论可解释。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentCommandTest extends EngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCommandTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void concurrentCommandsOnSameDeviceAreSerializedAndDeduplicated() throws Exception {
        // 先开灯
        postCommand("light-h1", Map.of("command", "turnOn",
                "idempotencyKey", "init-on-" + UUID.randomUUID()), true);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        String sharedIdem = "shared-idem-" + UUID.randomUUID();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 三个线程使用完全相同的幂等键，验证并发去重：只有一个执行
                    String idem = (idx == 5 || idx == 6 || idx == 7) ? sharedIdem
                            : "idem-cmd-" + idx + "-" + UUID.randomUUID();
                    int brightness = List.of(10, 20, 50, 100, 20, 50, 100, 10).get(idx);
                    JsonNode resp = postCommand("light-h1",
                            Map.of("command", "setBrightness",
                                    "params", Map.of("value", brightness),
                                    "idempotencyKey", idem), true);
                    outcomes.add("idx=" + idx + " key=" + idem + " cmd="
                            + resp.path("commandId").asText() + " outcome="
                            + resp.path("outcome").asText() + " reason="
                            + resp.path("rejectReason").asText(""));
                } catch (Exception e) {
                    errors.add(e.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发命令应在超时前全部完成");
        pool.shutdown();

        log.info("[并发命令测试] 关键输入=8并发setBrightness(3个共享幂等键) 结论分布={}",
                outcomes);
        assertEquals(List.of(), errors, "不应出现请求级异常");

        long effective = outcomes.stream().filter(o -> o.contains("outcome=EFFECTIVE")).count();
        long duplicate = outcomes.stream().filter(o -> o.contains("DUPLICATE_COMMAND")).count();
        log.info("[并发命令测试] 判定依据: EFFECTIVE={} DUPLICATE={} 全部结论={}",
                effective, duplicate, outcomes);
        // 5 个唯一键 + 3 个同键中的 1 个执行 = 6 个生效；同键另外 2 个被去重
        assertEquals(6, effective, "应只有 6 个命令真正串行生效");
        assertEquals(2, duplicate, "同一幂等键的并发命令必须恰好去重两个");
        assertEquals(threads, effective + duplicate, "所有请求都得到明确结论");

        JsonNode device = getDevice("light-h1");
        int finalBrightness = device.path("snapshot").path("attributes")
                .path("brightness").asInt();
        log.info("[并发命令测试] 最终状态 brightness={}（串行队列执行，无半更新）",
                finalBrightness);
        assertTrue(List.of(10, 20, 50, 100).contains(finalBrightness),
                "最终亮度必须是合法取值之一，且为某次完整命令的结果: " + finalBrightness);
    }

    private JsonNode postCommand(String deviceId, Map<String, Object> body, boolean wait)
            throws Exception {
        String url = "/api/devices/" + deviceId + "/commands" + (wait ? "?wait=true" : "");
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(url)
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
