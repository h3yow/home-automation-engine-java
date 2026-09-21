package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 执行记录（有界内存）：保存结论、失败诊断上下文与规则追踪链路。
 */
@Service
public class ExecutionLog {

    public void append(ExecutionRecord record) {
        // TODO 骨架
    }

    public List<ExecutionRecord> listByHousehold(String householdId, int limit) {
        // TODO 骨架
        return List.of();
    }

    public List<ExecutionRecord> listByDevice(String principalId, String deviceId, int limit) {
        // TODO 骨架
        return List.of();
    }

    public ExecutionRecord get(String principalId, String recordId) {
        // TODO 骨架
        return null;
    }
}
