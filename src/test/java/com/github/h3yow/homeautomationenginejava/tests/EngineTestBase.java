package com.github.h3yow.homeautomationenginejava.tests;

import com.github.h3yow.homeautomationenginejava.engine.TestIsolationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 集成测试基类：每个测试方法运行前把引擎恢复到演示数据基线，
 * 保证用例之间不存在排队命令、故障模式、规则启停、执行记录的状态泄漏。
 */
public abstract class EngineTestBase {

    @Autowired
    protected TestIsolationSupport isolation;

    @BeforeEach
    void resetEngineBaseline() {
        isolation.resetToBaseline();
    }
}
