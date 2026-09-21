package com.github.h3yow.homeautomationenginejava.model;

/**
 * 操作住户：归属于唯一家庭并携带角色。
 */
public record Resident(String residentId, String homeId, Role role) {
}
