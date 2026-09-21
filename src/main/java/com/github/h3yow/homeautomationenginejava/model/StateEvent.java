package com.github.h3yow.homeautomationenginejava.model;

import java.util.Map;

/**
 * 一次状态变化事件，驱动规则评估。
 * 由 StateService 在状态真正发生变化（版本被接受且属性有差异）时发出，
 * 重复/乱序上报被拒绝时不产生事件。
 *
 * @param causedByCommandId 非空表示本事件由该命令生效直接导致
 * @param propagationId     非空表示该命令归属于某条规则传播（链路上的事件路由依据），
 *                         设备自发上报与普通住户命令均为 null
 */
public record StateEvent(
        String deviceId,
        String homeId,
        long oldVersion,
        long newVersion,
        Map<String, Object> oldProperties,
        Map<String, Object> newProperties,
        boolean online,
        long atMillis,
        String causedByCommandId,
        String propagationId) {

    public StateEvent(String deviceId, String homeId, long oldVersion, long newVersion,
                      Map<String, Object> oldProperties, Map<String, Object> newProperties,
                      boolean online, long atMillis) {
        this(deviceId, homeId, oldVersion, newVersion, oldProperties, newProperties,
                online, atMillis, null, null);
    }

    public StateEvent withAttribution(String commandId, String propagationId) {
        return new StateEvent(deviceId, homeId, oldVersion, newVersion,
                oldProperties, newProperties, online, atMillis, commandId, propagationId);
    }
}
