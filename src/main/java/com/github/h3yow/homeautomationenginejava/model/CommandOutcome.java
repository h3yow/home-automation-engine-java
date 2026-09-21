package com.github.h3yow.homeautomationenginejava.model;

/**
 * 命令生命周期结论：
 * ACCEPTED 已受理（已进入设备命令队列，尚无执行结论）
 * EFFECTIVE 已生效（设备状态已收敛到命令目标）
 * REJECTED 已拒绝（语义/能力/模型校验不通过，或被风暴策略/冲突裁决拒绝）
 * TIMEOUT 超时（设备长时间无响应）
 * FAILED 执行失败（设备返回失败或离线）
 */
public enum CommandOutcome {
    ACCEPTED,
    EFFECTIVE,
    REJECTED,
    TIMEOUT,
    FAILED
}
