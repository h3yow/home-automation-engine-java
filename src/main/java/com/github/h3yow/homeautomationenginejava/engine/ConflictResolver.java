package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.Rule;
import com.github.h3yow.homeautomationenginejava.model.RuleAction;
import com.github.h3yow.homeautomationenginejava.model.RuleTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冲突裁决（确定性，与遍历顺序、时间精度无关）。
 *
 * <p>同一传播批次内，多条规则对同一设备给出指令时：
 * <ol>
 *   <li>先按“互斥命令对”识别冲突：不同命令文本（如 setPower ON/OFF）视为互斥；
 *       相同命令且归一化参数一致视为不冲突（可并存，各自执行，幂等去重由网关保证）；</li>
 *   <li>每个设备的互斥组内只允许一个胜出：
 *       priority 数值小者胜 → 相同 priority 取 ruleId 字典序小者 →
 *       同一规则的多个动作取声明顺序靠前者（actionIndex）；</li>
 *   <li>其余候选全部记为 CONFLICT_SUPPRESSED，detail 指出胜出规则与动作。</li>
 * </ol>
 * 比较器构成全序，输出 winners 按 (targetDeviceId) 排序，保证可复现。
 */
@Component
public class ConflictResolver {

    private static final Comparator<Candidate> ORDER =
            Comparator.comparingInt((Candidate c) -> c.rule().priority())
                    .thenComparing(c -> c.rule().ruleId())
                    .thenComparingInt(Candidate::actionIndex);

    public ResolvedBatch resolve(List<Candidate> candidates) {
        // 按目标设备分组，保持组内原始顺序仅用于记录；裁决只看全序比较器。
        Map<String, List<Candidate>> byDevice = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            byDevice.computeIfAbsent(c.action().targetDeviceId(), k -> new ArrayList<>()).add(c);
        }

        List<Candidate> winners = new ArrayList<>();
        List<RuleTrace.SuppressedAction> suppressed = new ArrayList<>();

        for (Map.Entry<String, List<Candidate>> group : byDevice.entrySet()) {
            List<Candidate> groupCandidates = group.getValue();
            // 在该设备上按“命令+归一化参数”划分子组；只有互斥子组内成员数>1才裁决。
            Map<String, List<Candidate>> byCommand = new LinkedHashMap<>();
            for (Candidate c : groupCandidates) {
                byCommand.computeIfAbsent(commandKey(c.action()), k -> new ArrayList<>()).add(c);
            }

            // 找出该设备所有互斥子组（命令文本不同的子组之间互斥）。
            if (byCommand.size() > 1) {
                // 整组互斥：全局最小者胜，其余全部抑制（包括与胜出者同命令的“其他规则”动作——
                // 因为它们与被抑制命令并列参与冲突，且同命令不同规则也意味着重复指令，
                // 统一以裁决结果为准，保证唯一确定性）。
                Candidate winner = groupCandidates.stream().min(ORDER)
                        .orElseThrow();
                winners.add(winner);
                for (Candidate c : groupCandidates) {
                    if (c != winner) {
                        suppressed.add(toSuppressed(c, winner));
                    }
                }
            } else {
                // 只有一个命令子组：参数一致的多个动作判为等价重复，保留排序最小者，
                // 其余抑制为冲突去重（解释：被同效果更高/同优先级动作代表）。
                List<Candidate> same = groupCandidates;
                if (same.size() == 1) {
                    winners.add(same.get(0));
                } else {
                    Candidate winner = same.stream().min(ORDER).orElseThrow();
                    winners.add(winner);
                    for (Candidate c : same) {
                        if (c != winner) {
                            suppressed.add(toSuppressed(c, winner));
                        }
                    }
                }
            }
        }

        winners.sort(Comparator.comparing(c -> c.action().targetDeviceId()));
        return new ResolvedBatch(List.copyOf(winners), List.copyOf(suppressed));
    }

    private RuleTrace.SuppressedAction toSuppressed(Candidate loser, Candidate winner) {
        return new RuleTrace.SuppressedAction(
                loser.rule().ruleId(),
                loser.action().targetDeviceId(),
                loser.action().command(),
                RejectReason.CONFLICT_SUPPRESSED,
                "suppressed by winner rule " + winner.rule().ruleId()
                        + " (winner priority=" + winner.rule().priority()
                        + ", actionIndex=" + winner.actionIndex()
                        + ", command=" + winner.action().command() + ")");
    }

    private String commandKey(RuleAction action) {
        // 参数参与命令键：相同命令但参数不同也算互斥（整设备只允许一个胜出），
        // 参数相同则为等价重复。键的构造按参数名字典序，确定无随机。
        StringBuilder sb = new StringBuilder(action.command()).append('{');
        if (action.params() != null) {
            action.params().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append('=')
                            .append(String.valueOf(e.getValue())).append(';'));
        }
        return sb.append('}').toString();
    }

    /** 参与裁决的候选动作，携带动作在规则内的声明下标。 */
    public record Candidate(Rule rule, RuleAction action, int actionIndex) {
        public Candidate(Rule rule, RuleAction action) {
            this(rule, action, indexOf(rule, action));
        }

        private static int indexOf(Rule rule, RuleAction action) {
            List<RuleAction> actions = rule.actions();
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i) == action) {
                    return i;
                }
            }
            return 0;
        }
    }

    /** 裁决结果：胜出列表（确定性顺序）与被抑制列表（含原因与胜出者）。 */
    public record ResolvedBatch(List<Candidate> winners,
                                List<RuleTrace.SuppressedAction> suppressed) {
    }
}
