package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CandidateAction;
import com.github.h3yow.homeautomationenginejava.model.ResolvedAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 冲突裁决器（确定性，不依赖遍历顺序或时间）。
 *
 * 分组：同家庭 + 同设备 + 同目标属性（命令改变的能力属性）的候选进入同一裁决组。
 * 组内规则：
 * 1) 与将胜出者参数完全一致（同 value）的候选视为重复动作，合并到胜者、不重复下发；
 * 2) 其余互斥候选按 (priority 降序, ruleId 字典序) 唯一确定胜者；
 * 3) 被抑制候选写入原因（胜出规则 + 优先级依据），可经 suppressionReason 查询。
 */
@Service
public class ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);

    private static final Comparator<CandidateAction> ORDER =
            Comparator.comparingInt(CandidateAction::priority).reversed()
                    .thenComparing(CandidateAction::ruleId);

    private final ConcurrentMap<String, String> suppressions = new ConcurrentHashMap<>();
    private final ModelRegistry modelRegistry;
    private final DeviceRegistry deviceRegistry;

    public ConflictResolver(ModelRegistry modelRegistry, DeviceRegistry deviceRegistry) {
        this.modelRegistry = modelRegistry;
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * 纯函数式裁决：不读写任何共享状态，相同候选输入恒定得到相同结果。
     * 试运行沙箱与真实执行共用本方法，保证两边裁决语义一致且互不干扰。
     * 真实执行在裁决后由调用方（RuleEngine）调用 {@link #recordSuppressions}
     * 把抑制原因落入可查询的共享记录。
     */
    public List<ResolvedAction> resolve(List<CandidateAction> candidates) {
        Map<String, List<CandidateAction>> groups = new LinkedHashMap<>();
        for (CandidateAction c : candidates.stream().sorted(ORDER).toList()) {
            String key = c.householdId() + "|" + c.deviceId() + "|" + targetAttr(c);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        List<ResolvedAction> results = new ArrayList<>();
        for (List<CandidateAction> group : groups.values()) {
            CandidateAction winner = group.get(0);
            Map<String, ResolvedAction.Suppression> suppressed = new LinkedHashMap<>();
            for (CandidateAction c : group.subList(1, group.size())) {
                String reason;
                if (sameParams(winner, c)) {
                    reason = "DUPLICATE: 与胜出规则 " + winner.ruleId()
                            + "（priority=" + winner.priority()
                            + "）动作参数完全一致，合并不重复下发";
                } else {
                    reason = "CONFLICT: 目标 " + c.deviceId() + "." + targetAttr(c)
                            + " 上与胜出规则 " + winner.ruleId()
                            + "（priority=" + winner.priority() + "）指令冲突；"
                            + "本规则 priority=" + c.priority()
                            + "，按(优先级降序,ruleId字典序)裁决被抑制";
                }
                suppressed.put(c.ruleId(),
                        new ResolvedAction.Suppression(c.ruleId(), winner.ruleId(), reason));
                log.warn("[冲突裁决-抑制] rule={} winner={} reason={}",
                        c.ruleId(), winner.ruleId(), reason);
            }
            results.add(new ResolvedAction(winner, Map.copyOf(suppressed)));
        }
        return results;
    }

    /**
     * 真实执行专用：把一轮裁决产生的抑制原因落入共享记录（供
     * {@code GET /api/records/suppressions/{ruleId}} 查询）。
     * 试运行沙箱不得调用本方法，保证推演不污染正式抑制记录。
     */
    public void recordSuppressions(List<ResolvedAction> resolved) {
        suppressions.clear();
        for (ResolvedAction ra : resolved) {
            ra.suppressedWithReason().forEach((ruleId, sup) ->
                    suppressions.put(ruleId, sup.reason()));
        }
    }

    public String suppressionReason(String ruleId) {
        return suppressions.get(ruleId);
    }

    /** 测试隔离：清空最近一轮抑制记录。 */
    public void clearSuppressions() {
        suppressions.clear();
    }

    /** 测试/内部辅助：最近一轮全部抑制原因。 */
    public Map<String, String> allSuppressions() {
        return Map.copyOf(suppressions);
    }

    private String targetAttr(CandidateAction c) {
        var device = deviceRegistry.find(c.deviceId());
        if (device == null) {
            return c.command();
        }
        var model = modelRegistry.modelFor(device);
        String attr = model.commandTarget().get(c.command());
        return attr == null ? c.command() : attr;
    }

    /**
     * 两候选是否为“重复动作”：命令必须相同；无参命令（turnOn/turnOff/open/close…）
     * 命令不同即为互斥；同命令再比较 value 参数。
     */
    private boolean sameParams(CandidateAction a, CandidateAction b) {
        if (!a.command().equals(b.command())) {
            return false;
        }
        Object va = a.params().get("value");
        Object vb = b.params().get("value");
        if (va == null && vb == null) {
            return true;
        }
        if (va instanceof Number x && vb instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(va, vb);
    }
}
