package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 命令下发请求。idempotencyKey 由调用方提供（同一来源的重复命令共用），
 * 用于重试去重，保证失败恢复不产生重复副作用。
 * sourceRuleId 非空表示系统内部自动化触发；null 表示用户入口。
 */
public record CommandRequest(
        String commandId,
        String deviceId,
        String householdId,
        String command,
        Map<String, Object> params,
        String idempotencyKey,
        String sourceRuleId,
        long deadlineAfterMillis
) {
}
