package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessControl;
import com.github.h3yow.homeautomationenginejava.engine.DryRunReport;
import com.github.h3yow.homeautomationenginejava.engine.DryRunReportStore;
import com.github.h3yow.homeautomationenginejava.engine.DryRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 试运行 / 复盘沙箱入口。
 * 推演在隔离的模拟世界中进行：不下发真实命令、不改变设备状态、不污染正式记录；
 * 报告独立存储、可查询，访问同样受家庭范围约束。
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private final AccessControl accessControl;
    private final DryRunService dryRunService;
    private final DryRunReportStore reportStore;

    public SandboxController(AccessControl accessControl, DryRunService dryRunService,
                             DryRunReportStore reportStore) {
        this.accessControl = accessControl;
        this.dryRunService = dryRunService;
        this.reportStore = reportStore;
    }

    @PostMapping("/dry-runs")
    public DryRunReport dryRun(@RequestHeader(value = "X-Principal-Id", required = false)
                               String principalId,
                               @RequestBody Dtos.DryRunRequestBody body) {
        if (body.householdId() == null || body.householdId().isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST: 试运行必须指定 householdId");
        }
        accessControl.requireHouseholdScope(principalId, body.householdId());
        List<DryRunService.Fragment> fragments = new ArrayList<>();
        if (body.fragments() != null) {
            for (Dtos.DryRunFragment f : body.fragments()) {
                if (f.deviceId() == null || f.deviceId().isBlank()) {
                    throw new IllegalArgumentException(
                            "INVALID_REQUEST: 片段必须包含 deviceId");
                }
                // 与真实上报入口一致：逐设备鉴权，越界/不存在即拒绝整个请求
                accessControl.requireDeviceAccess(principalId, f.deviceId());
                fragments.add(new DryRunService.Fragment(f.deviceId(), f.seq(),
                        f.online(), f.attributes() == null ? Map.of() : f.attributes()));
            }
        }
        long evaluationTime = body.evaluationTime() == null
                ? System.currentTimeMillis() : body.evaluationTime();
        DryRunService.Limits limits = body.limits() == null
                ? new DryRunService.Limits(null, null, null)
                : new DryRunService.Limits(body.limits().maxDepth(),
                        body.limits().maxEvents(), body.limits().maxSteps());
        return dryRunService.simulate(body.householdId(), evaluationTime, fragments, limits);
    }

    @GetMapping("/dry-runs/{dryRunId}")
    public DryRunReport get(@RequestHeader(value = "X-Principal-Id", required = false)
                            String principalId,
                            @PathVariable String dryRunId) {
        DryRunReport report = reportStore.get(dryRunId);
        if (report == null) {
            throw new IllegalArgumentException(
                    "UNKNOWN_DRY_RUN: 试运行报告不存在 " + dryRunId);
        }
        accessControl.requireHouseholdScope(principalId, report.householdId());
        return report;
    }

    @GetMapping("/dry-runs")
    public List<DryRunReport> list(@RequestHeader(value = "X-Principal-Id", required = false)
                                   String principalId,
                                   @RequestParam String householdId) {
        accessControl.requireHouseholdScope(principalId, householdId);
        return reportStore.listByHousehold(householdId);
    }
}
