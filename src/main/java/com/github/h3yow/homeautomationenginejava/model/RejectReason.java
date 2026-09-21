package com.github.h3yow.homeautomationenginejava.model;

/**
 * 拒绝/抑制的确定性原因码，所有拒绝必须可区分、可解释。
 */
public enum RejectReason {
    DEVICE_NOT_FOUND,
    /** 设备属于其他家庭，跨家庭访问 */
    CROSS_HOME_ACCESS,
    /** 角色权限不足（如 VIEWER 尝试写操作） */
    INSUFFICIENT_ROLE,
    /** 设备模型不支持该命令 */
    UNSUPPORTED_COMMAND,
    /** 引用了设备未声明的能力/属性 */
    UNKNOWN_CAPABILITY,
    /** 必填字段缺失且模型未提供默认值 */
    MISSING_REQUIRED_FIELD,
    /** 载荷类型/取值非法 */
    INVALID_PAYLOAD,
    /** 设备离线，命令被拒（区别于入队后超时） */
    DEVICE_OFFLINE,
    /** 背压：队列已满，按策略拒绝 */
    BACKPRESSURE_REJECTED,
    /** 冲突裁决中被更高优先级动作抑制 */
    CONFLICT_SUPPRESSED,
    /** 规则链式触发成环，链路被终止 */
    RULE_CYCLE,
    /** 乱序上报：版本号陈旧，拒绝回退 */
    STALE_REPORT
}
