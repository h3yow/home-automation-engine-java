package com.github.h3yow.homeautomationenginejava.api;

import java.util.Map;

/**
 * REST 层请求/响应 DTO。
 */
public final class Dtos {

    private Dtos() { }

    public record DispatchRequest(String residentId, String deviceId, String command,
                                  Map<String, Object> params, String commandId) { }

    public record ReportRequest(String deviceId, Long version, Boolean online,
                                Map<String, Object> properties) { }

    public record RuleRequest(String residentId, String ruleId, String name, int priority,
                              boolean enabled, Object condition,
                              java.util.List<Object> actions) { }

    public record ResultResponse(boolean accepted, String outcome, String rejectReason,
                                 String detail, long version) { }

    public record ReportResponse(boolean accepted, String rejectReason, String detail,
                                 long version, boolean triggered) { }

    public record ErrorResponse(String error, String rejectReason, String detail) { }
}
