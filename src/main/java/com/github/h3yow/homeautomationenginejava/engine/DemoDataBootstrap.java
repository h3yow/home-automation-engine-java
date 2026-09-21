package com.github.h3yow.homeautomationenginejava.engine;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 本地演示数据：两个家庭、若干模拟设备（灯/窗帘/温控/门锁）、
 * 常规规则、以及默认禁用的成环规则（可经 API 启用以验证环检测）。
 */
@Component
public class DemoDataBootstrap implements CommandLineRunner {

    @Override
    public void run(String... args) {
        // TODO 骨架
    }
}
