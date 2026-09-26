package com.github.h3yow.homeautomationenginejava.sandbox;

import java.util.List;
import java.util.Map;

/**
 * 试运行/复盘结构化报告。只存在于沙箱：绝不写入正式执行记录、成环记录或抑制记录。
 * 相同输入（含相同判定时间点）重复推演得到相同报告内容。
 */
public record DryRunReport(
        String reportId,
        String householdId,
        long evaluationTimeMillis,
        LimitsApplied limits,
        List<FragmentOutcome> fragmentOutcomes,
        List<String> firedRules,
        Map<String, String> suppressedActions,
        List<List<String>> cyclePaths,
        Map<String, ExpectedState> expectedStates,
        List<String> steps,
        boolean stepsTruncated,
        String termination
) {
    /** 本次推演实际生效的规模上限（请求值与引擎配置取较小者）。 */
    public record LimitsApplied(int maxDepth, int maxVisitedNodes, int maxReportEntries) {
    }

    /** 单条输入片段的采纳结论：与真实上报同一套机读原因。 */
    public record FragmentOutcome(
            int index,
            String deviceId,
            boolean accepted,
            String reason,
            String detail
    ) {
    }

    /** 推演结束后某台设备的预期状态。 */
    public record ExpectedState(
            String deviceId,
            boolean online,
            Map<String, Object> attributes
    ) {
    }

    /** 推演终止原因：正常完成 / 深度上限 / 节点上限。 */
    public enum Termination {
        COMPLETED,
        DEPTH_LIMIT_REACHED,
        NODE_LIMIT_REACHED
    }
}
