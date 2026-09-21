package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.CommandRequest;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 本地模拟硬件驱动。故障可按设备注入：
 * OFFLINE 离线；FAIL_ALWAYS 执行必失败；NO_RESPONSE 不返回（触发超时）。
 * 健康设备对幂等键做去重：重复执行只回放首次结论，不产生重复副作用。
 */
@Service
public class SimulatedDeviceDriver {

    public enum FaultMode { HEALTHY, OFFLINE, FAIL_ALWAYS, NO_RESPONSE }

    public record DriverOutcome(boolean success, RejectReason failureReason,
                                Map<String, Object> resultingAttributes, long seq) {
    }

    public void setFault(String deviceId, FaultMode mode) {
        // TODO 骨架
    }

    public FaultMode fault(String deviceId) {
        // TODO 骨架
        return FaultMode.HEALTHY;
    }

    /** 阻塞执行（由每设备单工作线程调用）；NO_RESPONSE 时将一直阻塞直到被中断。 */
    public DriverOutcome execute(Device device, CommandRequest request) {
        // TODO 骨架
        return null;
    }
}
