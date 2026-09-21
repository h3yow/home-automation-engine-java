package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 状态上报结论：是否被采纳、拒绝原因、采纳后的最新状态。
 */
public record ReportResult(
        String deviceId,
        boolean accepted,
        RejectReason rejectReason,
        String detail,
        long appliedSeq,
        Map<String, Object> latestAttributes
) {
}
