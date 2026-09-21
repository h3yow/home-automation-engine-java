package com.github.h3yow.homeautomationenginejava.model;

/**
 * 命令被拒绝/失败的可区分原因。
 */
public enum RejectReason {
    UNKNOWN_DEVICE,          // 设备不存在
    UNSUPPORTED_MODEL_VERSION, // 上报模型版本未登记且不兼容
    UNKNOWN_COMMAND,         // 模型未声明该命令
    UNKNOWN_CAPABILITY,      // 上报包含模型未知能力属性 -> 默认拒绝，不静默忽略
    MISSING_REQUIRED_FIELD,  // 命令缺少必填参数 / 上报缺少必填字段
    INVALID_VALUE,           // 参数取值不在模型声明范围内
    DEVICE_OFFLINE,          // 设备离线，不执行
    STORM_REJECTED,          // 风暴策略：拒绝
    STORM_DELAY_REJECTED,    // 风暴策略：延迟后仍无法入队
    CONFLICT_SUPPRESSED,     // 被更高优先级规则冲突裁决抑制
    DUPLICATE_COMMAND,       // 相同幂等键的命令已有结论
    MERGED,                  // 被合并进在队命令
    EXECUTION_FAILED,        // 设备执行返回失败
    NO_RESPONSE              // 设备无响应 -> 超时
}
