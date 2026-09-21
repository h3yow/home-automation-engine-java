package com.github.h3yow.homeautomationenginejava.api;

import org.springframework.web.bind.annotation.*;

/**
 * 设备入口：状态上报、状态查询、命令下发与结论查询、故障注入（测试用）。
 * 身份经 X-Principal-Id 头传递。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    @PostMapping("/{deviceId}/reports")
    public Object report(@RequestHeader("X-Principal-Id") String principalId,
                         @PathVariable String deviceId,
                         @RequestBody Dtos.ReportRequest body) {
        // TODO 骨架
        return null;
    }

    @GetMapping("/{deviceId}")
    public Object get(@RequestHeader("X-Principal-Id") String principalId,
                      @PathVariable String deviceId) {
        // TODO 骨架
        return null;
    }

    @PostMapping("/{deviceId}/commands")
    public Object command(@RequestHeader("X-Principal-Id") String principalId,
                          @PathVariable String deviceId,
                          @RequestBody Dtos.CommandRequestBody body) {
        // TODO 骨架
        return null;
    }

    @GetMapping("/{deviceId}/commands/{commandId}")
    public Object commandResult(@RequestHeader("X-Principal-Id") String principalId,
                                @PathVariable String deviceId,
                                @PathVariable String commandId) {
        // TODO 骨架
        return null;
    }

    @PostMapping("/{deviceId}/fault")
    public Object fault(@RequestHeader("X-Principal-Id") String principalId,
                        @PathVariable String deviceId,
                        @RequestBody Dtos.FaultBody body) {
        // TODO 骨架
        return null;
    }
}
