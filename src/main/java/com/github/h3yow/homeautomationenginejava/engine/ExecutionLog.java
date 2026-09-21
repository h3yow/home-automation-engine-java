package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 执行记录（有界内存，按家庭隔离）。
 * 每个家庭保留最近 N 条，禁止无界增长；查询经 AccessControl 校验家庭范围。
 */
@Service
public class ExecutionLog {

    private static final int MAX_PER_HOUSEHOLD = 500;

    private final ConcurrentMap<String, HouseholdLog> logs = new ConcurrentHashMap<>();

    /** 测试隔离：清空所有执行记录。 */
    public synchronized void clearAll() {
        logs.clear();
    }

    public void append(ExecutionRecord record) {
        logs.computeIfAbsent(record.householdId(), k -> new HouseholdLog()).add(record);
    }

    public List<ExecutionRecord> listByHousehold(String householdId, int limit) {
        HouseholdLog hLog = logs.get(householdId);
        if (hLog == null) {
            return List.of();
        }
        return hLog.recent(Math.max(1, Math.min(limit, MAX_PER_HOUSEHOLD)));
    }

    public List<ExecutionRecord> listByDevice(String householdId, String deviceId, int limit) {
        return listByHousehold(householdId, limit).stream()
                .filter(r -> deviceId.equals(r.deviceId()))
                .toList();
    }

    public ExecutionRecord get(String householdId, String recordId) {
        HouseholdLog hLog = logs.get(householdId);
        return hLog == null ? null : hLog.byId(recordId);
    }

    private static final class HouseholdLog {
        private final java.util.ArrayDeque<ExecutionRecord> deque = new java.util.ArrayDeque<>();
        private final ConcurrentMap<String, ExecutionRecord> byId = new ConcurrentHashMap<>();

        synchronized void add(ExecutionRecord record) {
            deque.addLast(record);
            byId.put(record.recordId(), record);
            while (deque.size() > MAX_PER_HOUSEHOLD) {
                ExecutionRecord removed = deque.removeFirst();
                byId.remove(removed.recordId());
            }
        }

        synchronized List<ExecutionRecord> recent(int limit) {
            return deque.stream()
                    .sorted(Comparator.comparingLong(ExecutionRecord::timestamp).reversed())
                    .limit(limit)
                    .toList();
        }

        ExecutionRecord byId(String id) {
            return byId.get(id);
        }
    }
}
