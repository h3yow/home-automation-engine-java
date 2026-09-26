package com.github.h3yow.homeautomationenginejava.sandbox;

import java.util.List;
import java.util.Map;

/**
 * 试运行/复盘请求：指定家庭、条件判定时间点与一组设备状态变化片段。
 * 片段允许乱序、重复、离线、包含能力范围外内容——与真实上报走同一套收敛语义。
 *
 * @param householdId          目标家庭（鉴权范围）
 * @param evaluationTimeMillis 条件判断使用的判定时间点（时间窗口条件以此为准）；
 *                             为 null 时由服务方以当前时间填充并回显，保证可重复
 * @param fragments            假设/历史状态变化片段，按给出顺序逐条应用
 * @param limits               本次推演的规模上限（null 表示全部使用引擎默认配置）
 */
public record DryRunRequest(
        String householdId,
        Long evaluationTimeMillis,
        List<StateFragment> fragments,
        Limits limits
) {
    /**
     * 一条设备状态变化片段（手工假设或历史片段）。
     * seq 可空：空表示由推演器按设备单调分配（与真实收敛器一致）。
     */
    public record StateFragment(
            String deviceId,
            Long seq,
            Boolean online,
            Map<String, Object> attributes
    ) {
    }

    /**
     * 单次推演规模上限；各字段为 null 或 <=0 时回落到引擎配置默认值，
     * 且最终生效值不会超过引擎配置上限（防止请求方放大规模）。
     */
    public record Limits(
            Integer maxDepth,
            Integer maxVisitedNodes,
            Integer maxReportEntries
    ) {
    }
}
