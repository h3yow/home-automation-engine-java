package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 设备状态上报（设备 -> 云），允许旧版本模型缺字段：
 * reportedVersion 为 null 时由服务端按确定性规则赋版本；
 * online 为 null 表示本次不更新在线态；properties 为增量 patch。
 */
public record StateReport(
        String deviceId,
        Long reportedVersion,
        Boolean online,
        Map<String, Object> properties) {
}
