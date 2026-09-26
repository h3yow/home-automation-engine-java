package com.github.h3yow.homeautomationenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.homeautomationenginejava.engine.RuleRegistry;
import com.github.h3yow.homeautomationenginejava.rule.CompositeCondition;
import com.github.h3yow.homeautomationenginejava.rule.Condition;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import com.github.h3yow.homeautomationenginejava.rule.RuleAction;
import com.github.h3yow.homeautomationenginejava.rule.StateCondition;
import com.github.h3yow.homeautomationenginejava.rule.TimeWindowCondition;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 试运行 / 复盘沙箱测试。
 * 覆盖：乱序/重复/离线/越界片段、真实成环、含时间条件的非成环长链路、
 * 与真实命令并发时的隔离与可重复性、容量上限截断、以及与真实执行一致的
 * 校验/越权/离线/冲突裁决语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DryRunSandboxTest extends EngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(DryRunSandboxTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RuleRegistry ruleRegistry;

    private final ObjectMapper json = new ObjectMapper();

    private static long noonToday() {
        return ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS)
                .plusHours(12).toInstant().toEpochMilli();
    }

    private static long lateNightToday() {
        return ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS)
                .plusHours(23).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------
    // 1) 乱序 / 重复 / 未知能力 / 离线片段：语义与真实上报一致，状态不回退
    // ------------------------------------------------------------------
    @Test
    void outOfOrderDuplicateOfflineFragmentsConvergeLikeRealReports() throws Exception {
        long noon = noonToday();
        JsonNode report = dryRun("alice", Map.of(
                "householdId", "H1",
                "evaluationTime", noon,
                "fragments", List.of(
                        Map.of("deviceId", "light2-h1", "seq", 10, "online", true,
                                "attributes", Map.of("power", "on", "brightness", 100,
                                        "color", "white")),
                        Map.of("deviceId", "light2-h1", "seq", 5, "online", true,
                                "attributes", Map.of("power", "off", "brightness", 10)),
                        Map.of("deviceId", "light2-h1", "seq", 10, "online", true,
                                "attributes", Map.of("power", "off", "brightness", 10)),
                        Map.of("deviceId", "light2-h1", "seq", 11, "online", true,
                                "attributes", Map.of("power", "on", "hackerAttr", 1)),
                        Map.of("deviceId", "light2-h1", "seq", 12, "online", false,
                                "attributes", Map.of()))));

        assertEquals("COMPLETED", report.path("terminationReason").asText());
        JsonNode outcomes = report.path("fragments");
        assertTrue(outcomes.get(0).path("accepted").asBoolean());
        assertFalse(outcomes.get(1).path("accepted").asBoolean());
        assertEquals("OUTDATED_SEQUENCE", outcomes.get(1).path("rejectReason").asText());
        assertFalse(outcomes.get(2).path("accepted").asBoolean());
        assertEquals("OUTDATED_SEQUENCE", outcomes.get(2).path("rejectReason").asText());
        assertEquals("UNKNOWN_CAPABILITY", outcomes.get(3).path("rejectReason").asText());
        assertTrue(outcomes.get(4).path("accepted").asBoolean(), "离线心跳应采纳");

        JsonNode expected = report.path("expectedStates").path("light2-h1");
        assertFalse(expected.path("online").asBoolean(), "末片段为离线，预期在线位=false");
        assertEquals(100, expected.path("attributes").path("brightness").asInt(),
                "乱序/重复片段不能把预期状态改回旧值");
        assertEquals("white", expected.path("attributes").path("color").asText());
        assertEquals("on", expected.path("attributes").path("power").asText());
        log.info("[乱序片段测试] 预期状态保持: {}", expected);

        // 隔离：真实设备状态与正式记录都不被试运行污染
        JsonNode realDevice = getDevice("alice", "light2-h1");
        assertTrue(realDevice.path("snapshot").path("attributes").path("brightness").isMissingNode(),
                "试运行不得改变真实设备状态");
        assertEquals(0, listRecords("alice", "H1").size(),
                "试运行不得写入正式执行记录");
    }

    // ------------------------------------------------------------------
    // 2) 真实成环：首尾相接才终止；环路径结构化；真实成环记录与执行记录不被污染
    // ------------------------------------------------------------------
    @Test
    void realCycleDetectedInSandboxWithoutPollutingRealCycleHistory() throws Exception {
        ruleRegistry.setEnabled("cycle-a-curtain-open-light-on", true);
        ruleRegistry.setEnabled("cycle-b-light-on-curtain-open", true);
        long noon = noonToday();

        JsonNode report = dryRun("alice", Map.of(
                "householdId", "H1",
                "evaluationTime", noon,
                "fragments", List.of(Map.of(
                        "deviceId", "curtain-h1", "seq", 10, "online", true,
                        "attributes", Map.of("position", "open")))));

        JsonNode cyclePath = report.path("cyclePath");
        assertFalse(cyclePath.isMissingNode(), "应返回真实成环链路");
        assertTrue(cyclePath.isArray());
        assertEquals(cyclePath.get(0).asText(), cyclePath.get(cyclePath.size() - 1).asText(),
                "环路径必须首尾相接指出闭合规则");
        String chain = cyclePath.toString();
        assertTrue(chain.contains("cycle-a-curtain-open-light-on"));
        assertTrue(chain.contains("cycle-b-light-on-curtain-open"));
        log.info("[试运行成环测试] 环路径: {}", chain);

        List<String> fired = jsonToStringList(report.path("firedRules"));
        assertTrue(fired.contains("cycle-a-curtain-open-light-on"));
        assertTrue(fired.contains("cycle-b-light-on-curtain-open"));
        assertEquals(2, fired.size(), "环上规则各只触发一次");
        assertEquals("COMPLETED", report.path("terminationReason").asText());

        // 隔离断言：真实成环记录为空、正式执行记录为空、真实状态未变
        JsonNode realCycle = getRealCycle("alice");
        assertFalse(realCycle.path("cycleDetected").asBoolean(),
                "试运行不得污染真实成环记录");
        assertEquals(0, listRecords("alice", "H1").size());
        assertTrue(getDevice("alice", "curtain-h1").path("snapshot")
                .path("attributes").path("position").isMissingNode());
        assertTrue(getDevice("alice", "light-h1").path("snapshot")
                .path("attributes").path("power").isMissingNode());
    }

    // ------------------------------------------------------------------
    // 3) 含时间条件的非成环长链路：必须推完后续影响；时间窗口外则在该规则处停止
    // ------------------------------------------------------------------
    @Test
    void longNonCyclicChainWithTimeConditionIsFullyPropagated() throws Exception {
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        upsertChain("dr-chain");
        long noon = noonToday();

        Map<String, Object> body = chainBody(noon);
        JsonNode report = dryRun("alice", body);

        assertEquals("COMPLETED", report.path("terminationReason").asText());
        assertTrue(report.path("cyclePath").isMissingNode()
                || report.path("cyclePath").isNull(), "非成环链路不得报告成环");
        assertEquals(List.of("dr-chain-1", "dr-chain-2", "dr-chain-3", "dr-chain-4"),
                jsonToStringList(report.path("firedRules")),
                "长链路必须逐层推完，不因子链较长而提前终止");
        assertFalse(report.path("suppressedActions").has("dr-chain-time"),
                "判定时间在窗口外：时间规则条件不满足，不进入冲突裁决，无抑制记录");

        JsonNode states = report.path("expectedStates");
        assertEquals("on", states.path("light-h1").path("attributes")
                .path("power").asText());
        assertEquals("closed", states.path("curtain-h1").path("attributes")
                .path("position").asText());
        assertEquals(true, states.path("lock-h1").path("attributes")
                .path("locked").asBoolean());
        assertEquals("on", states.path("light2-h1").path("attributes")
                .path("power").asText());
        assertEquals(35, states.path("thermo-h1").path("attributes")
                .path("currentTemp").asInt());
        log.info("[长链路测试] 窗口外时间规则不满足，长链路完整推完: fired={}",
                report.path("firedRules"));

        // 相同输入（含相同判定时间）重复推演：相同 dryRunId 与相同内容
        JsonNode again = dryRun("alice", body);
        assertEquals(report.path("dryRunId").asText(), again.path("dryRunId").asText(),
                "相同输入必须得到相同推演标识");
        assertEquals(report, again, "相同输入必须得到完全相同报告");

        // 判定时间落入夜间窗口：时间规则条件满足、进入同层冲突裁决，被确定抑制
        // （同优先级 ruleId 字典序，dr-chain-2 关窗帘胜出）；长链路依旧完整推完、非成环
        JsonNode night = dryRun("alice", chainBody(lateNightToday()));
        assertEquals("COMPLETED", night.path("terminationReason").asText());
        assertTrue(night.path("cyclePath").isMissingNode()
                || night.path("cyclePath").isNull());
        assertEquals(List.of("dr-chain-1", "dr-chain-2", "dr-chain-3", "dr-chain-4"),
                jsonToStringList(night.path("firedRules")),
                "被抑制的时间规则不触发；其余长链路完整推完");
        String timeSuppressed = night.path("suppressedActions")
                .path("dr-chain-time").asText();
        assertTrue(timeSuppressed.contains("CONFLICT"), "时间规则须被冲突抑制: "
                + timeSuppressed);
        assertTrue(timeSuppressed.contains("dr-chain-2"), "抑制原因须指出胜出规则");
        assertEquals("closed", night.path("expectedStates").path("curtain-h1")
                .path("attributes").path("position").asText(),
                "胜出动作（关窗帘）体现在预期状态，被抑制的开窗帘不生效");
        assertEquals(35, night.path("expectedStates").path("thermo-h1").path("attributes")
                .path("currentTemp").asInt(), "片段本身在两种判定时间下均被采纳");
    }

    // ------------------------------------------------------------------
    // 4) 多份试运行与真实命令/上报并发：互不影响；结果可重复；报告独立可查询
    // ------------------------------------------------------------------
    @Test
    void concurrentDryRunsAreIsolatedFromRealCommandsAndRepeatable() throws Exception {
        long noon = noonToday();
        Map<String, Object> body = Map.of(
                "householdId", "H1",
                "evaluationTime", noon,
                "fragments", List.of(Map.of(
                        "deviceId", "thermo-h1", "seq", 10, "online", true,
                        "attributes", Map.of("currentTemp", 35))));
        JsonNode first = dryRun("carol", body);
        String id = first.path("dryRunId").asText();
        assertEquals(List.of("rule-close-curtain-hot-day"),
                jsonToStringList(first.path("firedRules")));

        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);
        ConcurrentLinkedQueue<JsonNode> dryReports = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        // 4 个线程各做 3 次相同试运行
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3; i++) {
                        dryReports.add(dryRun("carol", body));
                    }
                } catch (Exception ex) {
                    failures.add(ex.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        // 2 个线程各发 3 条真实命令到 H2 的灯（与 H1 推演并行）
        AtomicInteger effective = new AtomicInteger();
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3; i++) {
                        JsonNode r = sendCommand("carol", "light-h2",
                                Map.of("command", "turnOn",
                                        "idempotencyKey", "real-" + UUID.randomUUID()), true);
                        if ("EFFECTIVE".equals(r.path("outcome").asText())) {
                            effective.incrementAndGet();
                        }
                    }
                } catch (Exception ex) {
                    failures.add(ex.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发阶段应在时限内完成");
        pool.shutdown();
        assertTrue(failures.isEmpty(), "并发阶段不应有异常: " + failures);

        // 所有试运行结果一致（id 与内容完全相同）
        assertEquals(12, dryReports.size());
        for (JsonNode r : dryReports) {
            assertEquals(id, r.path("dryRunId").asText(), "相同输入的并发推演必须同 id");
            assertEquals(first, r, "并发试运行结果必须彼此一致且与首发一致");
        }
        // 真实命令全部生效
        assertEquals(6, effective.get(), "并发真实命令必须全部真实生效");

        // H1 侧：试运行片段没有写进真实状态、没有产生正式记录
        assertTrue(getDevice("carol", "thermo-h1").path("snapshot").path("attributes")
                .path("currentTemp").isMissingNode());
        assertTrue(getDevice("carol", "curtain-h1").path("snapshot").path("attributes")
                .path("position").isMissingNode());
        assertEquals(0, listRecords("carol", "H1").size(),
                "试运行不得产生正式执行记录");
        // H2 侧：正式记录恰好只有 6 条真实用户命令
        JsonNode h2Records = listRecords("carol", "H2");
        assertEquals(6, h2Records.size());
        for (JsonNode r : h2Records) {
            assertTrue(r.path("sourceRuleId").isNull()
                    || r.path("sourceRuleId").asText().isBlank());
        }

        // 报告独立可查询
        JsonNode stored = getDryRun("carol", id);
        assertEquals(first, stored);
        JsonNode list = listDryRuns("carol", "H1");
        boolean found = false;
        for (JsonNode r : list) {
            found |= id.equals(r.path("dryRunId").asText());
        }
        assertTrue(found, "试运行报告应可按家庭列表查询");

        // 跨家庭访问报告被拒绝
        MvcResult denied = mockMvc.perform(get("/api/sandbox/dry-runs/" + id)
                        .header("X-Principal-Id", "bob"))
                .andReturn();
        assertEquals(403, denied.getResponse().getStatus());
    }

    // ------------------------------------------------------------------
    // 5) 容量上限：链路层数 / 访问节点数 / 返回条目数，截断原因可区分且有部分结果
    // ------------------------------------------------------------------
    @Test
    void capacityLimitsProduceDistinguishableTruncationAndPartialResults() throws Exception {
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        upsertChain("dr-limit");
        long noon = noonToday();

        JsonNode depthCut = dryRun("alice", chainBodyWithLimits(noon,
                Map.of("maxDepth", 2)));
        assertEquals("MAX_DEPTH_EXCEEDED", depthCut.path("terminationReason").asText());
        assertEquals(List.of("dr-limit-1", "dr-limit-2"),
                jsonToStringList(depthCut.path("firedRules")),
                "截断时保留已得到的部分结果");
        assertEquals("on", depthCut.path("expectedStates").path("light-h1")
                .path("attributes").path("power").asText());
        assertEquals("closed", depthCut.path("expectedStates").path("curtain-h1")
                .path("attributes").path("position").asText());
        assertTrue(depthCut.path("expectedStates").path("lock-h1").path("attributes")
                .path("locked").isMissingNode(), "被截断层之后的动作不得生效");
        assertTrue(depthCut.path("expectedStates").path("light2-h1").path("attributes")
                .path("power").isMissingNode(), "被截断层之后的动作不得生效");
        log.info("[容量测试] maxDepth=2 截断: {} fired={}",
                depthCut.path("terminationReason").asText(), depthCut.path("firedRules"));

        JsonNode eventCut = dryRun("alice", chainBodyWithLimits(noon,
                Map.of("maxEvents", 2)));
        assertEquals("MAX_EVENTS_EXCEEDED", eventCut.path("terminationReason").asText());
        assertEquals(List.of("dr-limit-1", "dr-limit-2"),
                jsonToStringList(eventCut.path("firedRules")),
                "事件数上限截断时保留已得到的部分结果");
        assertEquals("on", eventCut.path("expectedStates").path("light-h1")
                .path("attributes").path("power").asText());
        assertEquals("closed", eventCut.path("expectedStates").path("curtain-h1")
                .path("attributes").path("position").asText());
        assertTrue(eventCut.path("expectedStates").path("lock-h1").path("attributes")
                .path("locked").isMissingNode());

        JsonNode stepsCut = dryRun("alice", chainBodyWithLimits(noon,
                Map.of("maxSteps", 5)));
        assertTrue(stepsCut.path("stepsTruncated").asBoolean(),
                "返回步骤达到上限应标记 stepsTruncated");
        assertEquals(5, stepsCut.path("steps").size(), "步骤条目数有界");
        assertEquals("COMPLETED", stepsCut.path("terminationReason").asText(),
                "条目截断不应中断推演本身");
        assertEquals(4, stepsCut.path("firedRules").size(), "链路仍完整推完");
    }

    // ------------------------------------------------------------------
    // 6) 语义一致性：离线设备不生效、非法参数拒绝、跨家庭动作拒绝、冲突确定抑制
    // ------------------------------------------------------------------
    @Test
    void offlineInvalidValueCrossHouseholdAndConflictConclusionsMatchRealSemantics()
            throws Exception {
        ruleRegistry.setEnabled("rule-close-curtain-hot-day", false);
        long noon = noonToday();

        // 6.1 目标设备离线：动作给出明确不生效结论，不假成功
        ruleRegistry.upsert(new Rule("dr-offline", "H1", "离线目标规则", true, 10,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("curtain-h1", "close", Map.of()))));
        JsonNode offline = dryRun("alice", Map.of(
                "householdId", "H1",
                "evaluationTime", noon,
                "fragments", List.of(
                        Map.of("deviceId", "curtain-h1", "seq", 10,
                                "online", false, "attributes", Map.of()),
                        Map.of("deviceId", "thermo-h1", "seq", 11, "online", true,
                                "attributes", Map.of("currentTemp", 35)))));
        assertEquals(List.of("dr-offline"), jsonToStringList(offline.path("firedRules")));
        JsonNode offlineAction = findAction(offline, "dr-offline");
        assertEquals("FAILED", offlineAction.path("outcome").asText());
        assertEquals("DEVICE_OFFLINE", offlineAction.path("reason").asText());
        assertFalse(offline.path("expectedStates").path("curtain-h1").path("online").asBoolean());
        assertTrue(offline.path("expectedStates").path("curtain-h1").path("attributes")
                .path("position").isMissingNode());

        // 6.2 参数取值越界：与真实执行一致拒绝，不静默忽略
        ruleRegistry.upsert(new Rule("dr-invalid", "H1", "非法参数规则", true, 10,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("light2-h1", "setBrightness",
                        Map.of("value", 999)))));
        JsonNode invalid = dryRun("alice", singleThermoFragmentBody(noon, null));
        JsonNode invalidAction = findAction(invalid, "dr-invalid");
        assertEquals("REJECTED", invalidAction.path("outcome").asText());
        assertEquals("INVALID_VALUE", invalidAction.path("reason").asText());
        assertTrue(invalid.path("expectedStates").path("light2-h1").path("attributes")
                .path("brightness").isMissingNode());

        // 6.3 跨家庭自动化目标：确定拒绝并记录抑制原因
        ruleRegistry.upsert(new Rule("dr-cross", "H1", "跨家庭动作规则", true, 10,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("light-h2", "turnOn", Map.of()))));
        JsonNode cross = dryRun("carol", singleThermoFragmentBody(noon, null));
        JsonNode crossAction = findAction(cross, "dr-cross");
        assertEquals("REJECTED", crossAction.path("outcome").asText());
        assertEquals("SCOPE_DENIED", crossAction.path("reason").asText());
        assertTrue(cross.path("suppressedActions").path("dr-cross").asText()
                .startsWith("SCOPE_DENIED"));
        assertTrue(cross.path("expectedStates").path("light2-h1") == null
                        || cross.path("expectedStates").path("light2-h1").path("attributes")
                        .path("power").isMissingNode(),
                "H1 推演不应改动 H2 设备；H2 设备不出现在 H1 预期状态中");

        // 6.4 冲突裁决：高优先级 turnOn 胜出，低优先级 turnOff 被确定抑制
        ruleRegistry.upsert(new Rule("dr-hi", "H1", "开灯(高优先级)", true, 100,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("light2-h1", "turnOn", Map.of()))));
        ruleRegistry.upsert(new Rule("dr-lo", "H1", "关灯(低优先级)", true, 50,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("light2-h1", "turnOff", Map.of()))));
        JsonNode conflict = dryRun("alice", singleThermoFragmentBody(noon, null));
        String suppressed = conflict.path("suppressedActions").path("dr-lo").asText();
        assertTrue(suppressed.contains("CONFLICT"), "抑制原因必须可区分且确定: " + suppressed);
        assertTrue(suppressed.contains("dr-hi"), "抑制原因必须指出胜出规则");
        assertEquals("on", conflict.path("expectedStates").path("light2-h1")
                .path("attributes").path("power").asText(), "胜出动作必须体现在预期状态");
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 构造 4 级非成环链（设备互不回指）：
     * 温控高温 -> 灯开 -> 窗帘关 -> 门锁锁 -> 灯v2开。
     * 另有一条时间窗口规则（夜间才满足）在链路第二层被评估：
     * 窗口内触发为真实成环场景，窗口外则确定性不触发、长链继续推完。
     */
    private void upsertChain(String prefix) {
        ruleRegistry.upsert(new Rule(prefix + "-1", "H1", prefix + "-1", true, 10,
                new StateCondition("thermo-h1", "currentTemp",
                        StateCondition.Operator.GT, 28),
                List.of(new RuleAction("light-h1", "turnOn", Map.of()))));
        ruleRegistry.upsert(new Rule(prefix + "-2", "H1", prefix + "-2", true, 10,
                new StateCondition("light-h1", "power",
                        StateCondition.Operator.EQ, "on"),
                List.of(new RuleAction("curtain-h1", "close", Map.of()))));
        ruleRegistry.upsert(new Rule(prefix + "-3", "H1", prefix + "-3", true, 10,
                new StateCondition("curtain-h1", "position",
                        StateCondition.Operator.EQ, "closed"),
                List.of(new RuleAction("lock-h1", "lock", Map.of()))));
        ruleRegistry.upsert(new Rule(prefix + "-4", "H1", prefix + "-4", true, 10,
                new StateCondition("lock-h1", "locked",
                        StateCondition.Operator.EQ, true),
                List.of(new RuleAction("light2-h1", "turnOn", Map.of()))));
        // 时间条件规则：夜间且灯已开时要“开窗帘”，与同层 dr-*-2 的“关窗帘”
        // 作用于同设备同目标属性 -> 进入冲突裁决：同优先级按 ruleId 字典序，
        // dr-*-2 确定胜出、本规则被 CONFLICT 抑制（无有效动作 -> 不产生回指事件，
        // 故整条长链非成环并继续推完）。白天窗口外条件直接不满足，不进入裁决。
        Condition nightAndLightOn = new CompositeCondition(CompositeCondition.Logic.ALL,
                List.of(new StateCondition("light-h1", "power",
                                StateCondition.Operator.EQ, "on"),
                        new TimeWindowCondition(22 * 3600, 6 * 3600)));
        ruleRegistry.upsert(new Rule(prefix + "-time", "H1", prefix + "-time",
                true, 10, nightAndLightOn,
                List.of(new RuleAction("curtain-h1", "open", Map.of()))));
    }

    private Map<String, Object> chainBody(long evalTime) {
        return chainBodyWithLimits(evalTime, null);
    }

    private Map<String, Object> chainBodyWithLimits(long evalTime,
                                                    Map<String, Integer> limits) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("householdId", "H1");
        body.put("evaluationTime", evalTime);
        body.put("fragments", List.of(Map.of(
                "deviceId", "thermo-h1", "seq", 10, "online", true,
                "attributes", Map.of("currentTemp", 35))));
        if (limits != null) {
            body.put("limits", limits);
        }
        return body;
    }

    private Map<String, Object> singleThermoFragmentBody(long evalTime,
                                                         Map<String, Integer> limits) {
        return chainBodyWithLimits(evalTime, limits);
    }

    private JsonNode findAction(JsonNode report, String ruleId) {
        for (JsonNode action : report.path("actions")) {
            if (ruleId.equals(action.path("ruleId").asText())) {
                return action;
            }
        }
        fail("未找到规则 " + ruleId + " 的动作结论");
        return null;
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

    private JsonNode getDryRun(String principal, String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sandbox/dry-runs/" + id)
                        .header("X-Principal-Id", principal))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listDryRuns(String principal, String householdId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sandbox/dry-runs")
                        .param("householdId", householdId)
                        .header("X-Principal-Id", principal))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getDevice(String principal, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/devices/" + deviceId)
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getRealCycle(String principal) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records/cycles/H1")
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listRecords(String principal, String householdId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/records")
                        .param("householdId", householdId)
                        .param("limit", "200")
                        .header("X-Principal-Id", principal))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode sendCommand(String principal, String deviceId,
                                 Map<String, Object> body, boolean wait) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/devices/" + deviceId + "/commands" + (wait ? "?wait=true" : ""))
                        .header("X-Principal-Id", principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }
}
