package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CandidateAction;
import com.github.h3yow.homeautomationenginejava.model.ResolvedAction;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 冲突裁决器：
 * 同一设备同一目标属性上的互斥命令构成冲突；
 * 裁决确定性地按 (priority 降序, ruleId 字典序) 选胜者，
 * 同参数同值命令视为重复并合并，其余候选带原因被抑制、可查询。
 */
@Service
public class ConflictResolver {

    public List<ResolvedAction> resolve(List<CandidateAction> candidates) {
        // TODO 骨架
        return List.of();
    }

    /** 查询某条规则最近一次被抑制的具体原因。 */
    public String suppressionReason(String ruleId) {
        // TODO 骨架
        return null;
    }
}
