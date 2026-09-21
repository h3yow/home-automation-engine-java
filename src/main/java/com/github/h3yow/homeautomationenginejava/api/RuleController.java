package com.github.h3yow.homeautomationenginejava.api;

import org.springframework.web.bind.annotation.*;

/**
 * 规则管理入口：创建/更新/查询/启用-禁用/删除。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    @PutMapping("/{ruleId}")
    public Object upsert(@RequestHeader("X-Principal-Id") String principalId,
                         @PathVariable String ruleId,
                         @RequestBody Dtos.RuleBody body) {
        // TODO 骨架
        return null;
    }

    @GetMapping("/{ruleId}")
    public Object get(@RequestHeader("X-Principal-Id") String principalId,
                      @PathVariable String ruleId) {
        // TODO 骨架
        return null;
    }

    @GetMapping
    public Object list(@RequestHeader("X-Principal-Id") String principalId,
                       @RequestParam(required = false) String householdId) {
        // TODO 骨架
        return null;
    }

    @DeleteMapping("/{ruleId}")
    public Object delete(@RequestHeader("X-Principal-Id") String principalId,
                         @PathVariable String ruleId) {
        // TODO 骨架
        return null;
    }
}
