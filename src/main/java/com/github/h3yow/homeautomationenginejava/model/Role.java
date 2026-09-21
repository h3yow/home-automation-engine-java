package com.github.h3yow.homeautomationenginejava.model;

/**
 * 住户角色：权限从高到低。
 * OWNER 可管理规则与设备；MEMBER 可下发命令；VIEWER 只读。
 */
public enum Role {
    OWNER,
    MEMBER,
    VIEWER
}
