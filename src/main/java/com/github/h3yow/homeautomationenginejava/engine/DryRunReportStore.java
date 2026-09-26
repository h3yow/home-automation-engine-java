package com.github.h3yow.homeautomationenginejava.engine;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 试运行报告存储（有界内存，按家庭隔离）。
 * 与正式执行记录（ExecutionLog）完全分离：试运行结论永不混入正式记录。
 * 相同内容哈希的推演幂等回放：putIfAbsent 返回已存在的同 id 报告。
 */
@Service
public class DryRunReportStore {

    private static final int MAX_PER_HOUSEHOLD = 100;

    private final ConcurrentMap<String, DryRunReport> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayDeque<String>> idsByHousehold =
            new ConcurrentHashMap<>();

    /** 幂等存入：同 id 已存在时直接返回已有报告（相同输入重复推演结果一致）。 */
    public synchronized DryRunReport putIfAbsent(DryRunReport report) {
        DryRunReport existing = byId.get(report.dryRunId());
        if (existing != null) {
            return existing;
        }
        byId.put(report.dryRunId(), report);
        ArrayDeque<String> ids = idsByHousehold.computeIfAbsent(
                report.householdId(), k -> new ArrayDeque<>());
        ids.addLast(report.dryRunId());
        while (ids.size() > MAX_PER_HOUSEHOLD) {
            String evicted = ids.removeFirst();
            byId.remove(evicted);
        }
        return report;
    }

    public DryRunReport get(String dryRunId) {
        return byId.get(dryRunId);
    }

    public List<DryRunReport> listByHousehold(String householdId) {
        ArrayDeque<String> ids = idsByHousehold.get(householdId);
        if (ids == null) {
            return List.of();
        }
        List<DryRunReport> reports;
        synchronized (this) {
            reports = ids.stream().map(byId::get)
                    .filter(java.util.Objects::nonNull).toList();
        }
        return reports.stream()
                .sorted(Comparator.comparingLong(DryRunReport::generatedAt).reversed())
                .toList();
    }

    /** 测试隔离：清空全部试运行报告。 */
    public synchronized void clearAll() {
        byId.clear();
        idsByHousehold.clear();
    }
}
