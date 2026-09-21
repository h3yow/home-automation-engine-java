package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.ExecutionRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计/执行记录：每个家庭一条有界环形队列（容量固定，禁止无界增长）。
 *
 * <p>记录内容携带结果五态与拒绝原因；失败记录的 detail 保留可诊断上下文
 * （命令 ID、规则 ID、原因文本）。查询只能在 Facade 完成家庭授权后进行。
 */
@Component
public class AuditLog {

    /** 每家庭保留最近记录条数（有界）。 */
    private static final int MAX_PER_HOME = 1_000;

    private final Map<String, Deque<ExecutionRecord>> byHome = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public void record(ExecutionRecord record) {
        if (record == null || record.homeId() == null) {
            return;
        }
        Deque<ExecutionRecord> deque =
                byHome.computeIfAbsent(record.homeId(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(record);
            while (deque.size() > MAX_PER_HOME) {
                deque.pollFirst();
            }
        }
    }

    /**
     * 按家庭查询（调用前必须已完成授权），可按设备过滤；最新在前，最多 limit 条。
     */
    public List<ExecutionRecord> query(String homeId, String deviceId, int limit) {
        Deque<ExecutionRecord> deque = byHome.get(homeId);
        if (deque == null) {
            return List.of();
        }
        int cap = limit <= 0 ? 50 : Math.min(limit, MAX_PER_HOME);
        List<ExecutionRecord> out = new ArrayList<>(cap);
        synchronized (deque) {
            // 逆序拷贝（最新在前），遍历期间不受并发写入影响。
            ExecutionRecord[] all = deque.toArray(new ExecutionRecord[0]);
            for (int i = all.length - 1; i >= 0 && out.size() < cap; i--) {
                ExecutionRecord r = all[i];
                if (deviceId == null || deviceId.equals(r.deviceId())) {
                    out.add(r);
                }
            }
        }
        return List.copyOf(out);
    }

    public long nextSequence() {
        return sequence.incrementAndGet();
    }
}
