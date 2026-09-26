package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.sandbox.DryRunReport;
import com.github.h3yow.homeautomationenginejava.sandbox.DryRunRequest;
import com.github.h3yow.homeautomationenginejava.sandbox.DryRunService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试运行/复盘入口：在沙箱中按当前生效规则完整推演一组假设/历史状态片段，
 * 返回结构化报告；与真实执行完全隔离，不产生任何正式记录。
 */
@RestController
@RequestMapping("/api/dry-run")
public class DryRunController {

    private final DryRunService dryRunService;

    public DryRunController(DryRunService dryRunService) {
        this.dryRunService = dryRunService;
    }

    @PostMapping
    public DryRunReport dryRun(@RequestHeader(value = "X-Principal-Id", required = false)
                               String principalId,
                               @RequestBody DryRunRequest body) {
        return dryRunService.dryRun(principalId, body);
    }
}
