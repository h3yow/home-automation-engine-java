package com.github.h3yow.homeautomationenginejava.model;

/**
 * 命令风暴（单设备待执行队列打满）时的策略。
 * REJECT 立即拒绝；DELAY 入队等待，超过延迟上限仍拒绝；MERGE 与在队同类命令合并。
 */
public enum BackpressurePolicy {
    REJECT,
    DELAY,
    MERGE
}
