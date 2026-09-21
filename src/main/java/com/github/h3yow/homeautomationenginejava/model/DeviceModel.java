package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;
import java.util.Set;

/**
 * 设备能力模型（按 modelId + version 登记）。
 * supportedCommands: 命令名 -> 该命令必填参数名集合
 * commandTarget: 命令生效后改变的能力属性名（用于冲突判定与生效确认）
 * capabilities: 允许上报的能力属性名集合；集合外的属性一律拒绝（未知能力不静默忽略）
 * requiredReportFields: 状态上报必填字段
 * commandValueDomain: 命令参数 -> 允许的离散/范围取值（null 表示不校验离散值）
 * commandResult: 无参命令（如 open/close/lock）生效后写入的常量属性值；
 *   有参命令的值优先取参数 value
 */
public record DeviceModel(
        String modelId,
        int version,
        Set<String> supportedCommands,
        Map<String, Set<String>> commandParams,
        Map<String, String> commandTarget,
        Map<String, Object> commandResult,
        Set<String> capabilities,
        Set<String> requiredReportFields,
        Map<String, Object> commandValueDomain
) {
}
