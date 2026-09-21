package com.github.h3yow.homeautomationenginejava.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次事件传播的规则追踪链路：
 * 哪些规则被评估、条件判定依据、哪些动作执行/被抑制、在何处检测到环。
 */
public class RuleTrace {

    private final String rootEventId;
    private final List<String> steps = new ArrayList<>();
    private List<String> cycle = null;

    public RuleTrace(String rootEventId) {
        this.rootEventId = rootEventId;
    }

    public String rootEventId() {
        return rootEventId;
    }

    public void step(String message) {
        steps.add(message);
    }

    public void markCycle(List<String> cyclePath) {
        this.cycle = List.copyOf(cyclePath);
    }

    public boolean cycleDetected() {
        return cycle != null;
    }

    public List<String> cyclePath() {
        return cycle;
    }

    public List<String> steps() {
        return List.copyOf(steps);
    }
}
