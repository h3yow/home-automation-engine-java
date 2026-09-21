package com.github.h3yow.homeautomationenginejava.api;

import org.springframework.web.bind.annotation.*;

/**
 * 执行记录查询入口（含失败诊断上下文与规则链路），以及被抑制动作原因查询。
 */
@RestController
@RequestMapping("/api/records")
public class LogController {

    @GetMapping
    public Object list(@RequestHeader("X-Principal-Id") String principalId,
                       @RequestParam(required = false) String deviceId,
                       @RequestParam(defaultValue = "50") int limit) {
        // TODO 骨架
        return null;
    }

    @GetMapping("/{recordId}")
    public Object get(@RequestHeader("X-Principal-Id") String principalId,
                      @PathVariable String recordId) {
        // TODO 骨架
        return null;
    }

    @GetMapping("/suppressions/{ruleId}")
    public Object suppression(@RequestHeader("X-Principal-Id") String principalId,
                              @PathVariable String ruleId) {
        // TODO 骨架
        return null;
    }
}
