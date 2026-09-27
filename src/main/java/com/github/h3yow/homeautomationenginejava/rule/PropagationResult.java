package com.github.h3yow.homeautomationenginejava.rule;

import java.util.List;
import java.util.Map;

/**
 * 一次状态事件传播的完整结论：执行的命令、被抑制动作及原因、成环路径（若有）、
 * 终止原因（正常收敛 / 成环 / 容量截断）。
 */
public record PropagationResult(
        String rootEventId,
        List<String> firedRules,
        List<String> dispatchedCommandIds,
        Map<String, String> suppressedActions,
        List<String> cyclePath,
        TerminationReason terminationReason,
        RuleTrace trace
) {

    /**
     * 传播终止原因。成环与容量截断必须可区分：
     * 成环意味着首尾相接的规则会无限相互触发；截断只是到达安全上限，
     * 已得到的 firedRules/dispatchedCommandIds 等部分结果仍然有效。
     */
    public enum TerminationReason {
        COMPLETED,            // 无命中规则，传播自然收敛
        CYCLE_DETECTED,       // 条件成立的回指规则闭合，真实成环
        MAX_DEPTH_EXCEEDED,   // 链路层数达到上限，截断（非成环）
        MAX_EVENTS_EXCEEDED   // 访问事件节点数达到上限，截断（非成环）
    }
}
