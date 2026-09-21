package com.github.h3yow.homeautomationenginejava.engine;

import org.springframework.stereotype.Service;

/**
 * 多住户访问控制：所有入口（含内部自动化）在触碰设备/规则/记录前都必须经过此处校验。
 */
@Service
public class AccessControl {

    public void registerPrincipal(Principal principal) {
        // TODO 骨架
    }

    public Principal requirePrincipal(String principalId) {
        // TODO 骨架
        return null;
    }

    public void requireDeviceAccess(String principalId, String deviceId) {
        // TODO 骨架
    }

    public void requireRuleAccess(String principalId, String ruleId) {
        // TODO 骨架
    }

    public void requireHouseholdScope(String principalId, String householdId) {
        // TODO 骨架
    }

    /** 内部自动化动作的越界校验：规则所属家庭必须包含目标设备。 */
    public void requireRuleActionScope(String ruleId, String targetDeviceId) {
        // TODO 骨架
    }
}
