package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandResult;
import com.github.h3yow.homeautomationenginejava.model.DeviceCommand;

/**
 * 规则动作 -> 命令通道 的端口。
 * 实现端负责自动化动作的家庭边界授权（系统内部动作同样不得越界）与留痕。
 */
public interface ActionDispatcher {
    CommandResult dispatchAutomation(String ruleHomeId, String ruleId, DeviceCommand command);
}
