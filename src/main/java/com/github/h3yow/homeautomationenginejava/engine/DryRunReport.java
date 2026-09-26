package com.github.h3yow.homeautomationenginejava.engine;

import java.util.List;
import java.util.Map;

/**
 * 一次试运行/复盘推演的结构化报告。
 *
 * 报告只读、可查询，独立于正式执行记录（ExecutionLog）存储；
 * 相同输入（含相同判定时间与相同环境指纹）重复推演得到相同 dryRunId 与相同内容。
 */
public record DryRunReport(
        String dryRunId,
        String householdId,
        long evaluationTime,
        long generatedAt,
        TerminationReason terminationReason,
        boolean stepsTruncated,
        List<FragmentOutcome> fragments,
        List<StepEntry> steps,
        List<String> firedRules,
        Map<String, String> suppressedActions,
        List<ActionOutcome> actions,
        List<String> cyclePath,
        Map<String, ExpectedState> expectedStates
) {

    /** 推演整体终止原因（成环记录于 cyclePath，不属于截断）。 */
    public enum TerminationReason {
        COMPLETED,            // 全部片段处理完毕、传播收敛
        MAX_DEPTH_EXCEEDED,   // 触发链层数达到上限，截断
        MAX_EVENTS_EXCEEDED   // 访问事件节点数达到上限，截断
    }

    /** 单个输入片段的收敛结论（语义与真实上报一致）。 */
    public record FragmentOutcome(
            int index,
            String deviceId,
            boolean accepted,
            String rejectReason,
            String detail,
            long appliedSeq
    ) {
    }

    /** 一步推演的判定依据（条件评估、冲突裁决、动作结论、成环、截断等）。 */
    public record StepEntry(
            int seq,
            String phase,
            String message
    ) {
    }

    /** 一个胜出动作的推演结论（不会真正下发）。 */
    public record ActionOutcome(
            String ruleId,
            String deviceId,
            String command,
            String outcome,
            String reason,
            Map<String, Object> effectiveAttributes
    ) {
    }

    /** 一台设备在推演后的预期状态。 */
    public record ExpectedState(
            String deviceId,
            boolean online,
            Map<String, Object> attributes
    ) {
    }
}
