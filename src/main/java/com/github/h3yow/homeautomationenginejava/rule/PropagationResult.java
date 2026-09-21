package com.github.h3yow.homeautomationenginejava.rule;

import java.util.List;
import java.util.Map;

/**
 * 一次状态事件的完整传播结论：执行的命令、被抑制动作及原因、成环路径（若有）。
 */
public record PropagationResult(
        String rootEventId,
        List<String> firedRules,
        List<String> dispatchedCommandIds,
        Map<String, String> suppressedActions,
        List<String> cyclePath,
        RuleTrace trace
) {
}
