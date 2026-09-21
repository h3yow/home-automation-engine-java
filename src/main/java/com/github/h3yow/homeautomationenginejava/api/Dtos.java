package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.rule.Condition;

import java.util.List;
import java.util.Map;

/**
 * REST 层数据传输对象集合。
 */
public final class Dtos {

    private Dtos() {
    }

    public record ReportRequest(String modelId, Integer modelVersion, Long seq,
                                Boolean online, Map<String, Object> attributes) {
    }

    public record CommandRequestBody(String command, Map<String, Object> params,
                                     String idempotencyKey, Long timeoutMillis) {
    }

    public record RuleBody(String name, String householdId, Boolean enabled, Integer priority,
                           Object condition, List<ActionBody> actions) {
    }

    public record ActionBody(String deviceId, String command, Map<String, Object> params) {
    }

    public record RuleView(String ruleId, String name, String householdId, boolean enabled,
                           int priority, Condition condition, List<ActionBody> actions) {
    }

    public record FaultBody(String mode) {
    }

    public record ErrorBody(String error, String reason, String detail) {
    }
}
