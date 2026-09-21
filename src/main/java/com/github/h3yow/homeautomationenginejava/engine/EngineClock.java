package com.github.h3yow.homeautomationenginejava.engine;

/**
 * 可替换时钟：生产用系统时钟，测试可注入受控时钟以确定性验证时间窗口与超时。
 */
public interface EngineClock {
    long nowMillis();
}
