package com.github.h3yow.homeautomationenginejava.model;

/**
 * 一台模拟设备。
 */
public record Device(
        String deviceId,
        String householdId,
        String modelId,
        int modelVersion,
        String name
) {
}
