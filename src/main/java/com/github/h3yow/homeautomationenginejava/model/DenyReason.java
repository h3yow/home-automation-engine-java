package com.github.h3yow.homeautomationenginejava.model;

/**
 * 多住户越权访问的可区分原因。
 */
public enum DenyReason {
    MISSING_IDENTITY,        // 未提供调用身份
    UNKNOWN_PRINCIPAL,       // 身份不存在
    HOUSEHOLD_MISMATCH,      // 跨家庭访问
    DEVICE_NOT_AUTHORIZED,   // 对设备无权限
    RULE_NOT_AUTHORIZED,     // 对规则无权限
    ACTION_TARGET_OUT_OF_SCOPE, // 规则动作目标超出规则所属家庭
    RECORD_NOT_AUTHORIZED    // 对执行记录无权限
}
