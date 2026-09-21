package com.github.h3yow.homeautomationenginejava.model;

/**
 * 命令生命周期结果（五态可区分）。
 */
public enum CommandOutcome {
    /** 已受理：通过校验并入队，尚未被设备执行 */
    ACCEPTED,
    /** 已生效：设备状态已收敛到命令目标 */
    EFFECTIVE,
    /** 已拒绝：未进入执行，rejectReason 给出确定性原因 */
    REJECTED,
    /** 超时：设备在时限内无响应/无生效确认 */
    TIMEOUT,
    /** 执行失败：设备执行了但返回失败，不得记录为成功 */
    FAILED
}
