package com.github.h3yow.homeautomationenginejava.rule;

/**
 * 规则条件。evaluate 必须是纯函数式判定：只依据上下文，返回明确的 true/false。
 */
public interface Condition {
    boolean evaluate(TriggerContext ctx);

    /** 可读描述，用于日志中的“判定依据”。 */
    String describe();
}
