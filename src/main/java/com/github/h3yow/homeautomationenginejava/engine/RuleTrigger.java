package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.RuleTrace;
import com.github.h3yow.homeautomationenginejava.model.StateEvent;

/**
 * 状态 -> 规则 的触发端口，用于打断 StateService 与 RuleEngine 的构造期循环依赖。
 */
public interface RuleTrigger {
    RuleTrace onStateEvent(StateEvent event);
}
