package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 设备状态快照。seq 为单调序号（设备上报携带或由收敛器代填）；
 * online 独立于能力属性，离线不得覆盖能力属性、不得被记录为成功。
 */
public record StateSnapshot(
        String deviceId,
        long seq,
        boolean online,
        Map<String, Object> attributes,
        long timestamp
) {
}
