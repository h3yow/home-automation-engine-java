package com.github.h3yow.homeautomationenginejava.model;

/**
 * 上报处理结果。
 * accepted=false 时 reason 必为确定性原因（如 STALE_REPORT / UNKNOWN_CAPABILITY）；
 * accepted=true 且产生了真实状态变化时 event 非空，用于触发规则。
 */
public record ReportResult(
        boolean accepted,
        RejectReason reason,
        String detail,
        long newVersion,
        StateEvent event) {
}
