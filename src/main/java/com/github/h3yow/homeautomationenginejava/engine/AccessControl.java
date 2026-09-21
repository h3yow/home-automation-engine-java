package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DenyReason;
import com.github.h3yow.homeautomationenginejava.model.Device;
import com.github.h3yow.homeautomationenginejava.rule.Rule;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 多住户访问控制。所有入口（HTTP 用户入口与内部自动化入口）在触碰资源前统一在此校验，
 * 拒绝原因可机读区分（见 {@link DenyReason}）。
 */
@Service
public class AccessControl {

    private final ConcurrentMap<String, Principal> principals = new ConcurrentHashMap<>();
    private final DeviceRegistry deviceRegistry;
    private final RuleRegistry ruleRegistry;

    public AccessControl(DeviceRegistry deviceRegistry, RuleRegistry ruleRegistry) {
        this.deviceRegistry = deviceRegistry;
        this.ruleRegistry = ruleRegistry;
    }

    public void registerPrincipal(Principal principal) {
        principals.put(principal.principalId(), principal);
    }

    /** 解析身份头：缺失/未知分别给出不同原因。 */
    public Principal requirePrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            throw new AccessDeniedException(DenyReason.MISSING_IDENTITY, "缺少 X-Principal-Id 身份头");
        }
        Principal principal = principals.get(principalId);
        if (principal == null) {
            throw new AccessDeniedException(DenyReason.UNKNOWN_PRINCIPAL, "未知身份: " + principalId);
        }
        return principal;
    }

    public void requireHouseholdScope(String principalId, String householdId) {
        Principal principal = requirePrincipal(principalId);
        if (!principal.householdIds().contains(householdId)) {
            throw new AccessDeniedException(DenyReason.HOUSEHOLD_MISMATCH,
                    "身份 " + principalId + " 不属于家庭 " + householdId);
        }
    }

    public void requireDeviceAccess(String principalId, String deviceId) {
        Principal principal = requirePrincipal(principalId);
        Device device = deviceRegistry.find(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("UNKNOWN_DEVICE: 设备不存在 " + deviceId);
        }
        if (!principal.householdIds().contains(device.householdId())) {
            throw new AccessDeniedException(DenyReason.DEVICE_NOT_AUTHORIZED,
                    "身份 " + principalId + " 对设备 " + deviceId + "（家庭 "
                            + device.householdId() + "）无权限");
        }
    }

    public void requireRuleAccess(String principalId, String ruleId) {
        Principal principal = requirePrincipal(principalId);
        Rule rule = ruleRegistry.get(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("UNKNOWN_RULE: 规则不存在 " + ruleId);
        }
        if (!principal.householdIds().contains(rule.householdId())) {
            throw new AccessDeniedException(DenyReason.RULE_NOT_AUTHORIZED,
                    "身份 " + principalId + " 对规则 " + ruleId + "（家庭 "
                            + rule.householdId() + "）无权限");
        }
    }

    /**
     * 内部自动化动作越界校验：规则只能作用于同一家庭的设备。
     */
    public void requireRuleActionScope(String ruleId, String targetDeviceId) {
        Rule rule = ruleRegistry.get(ruleId);
        if (rule == null) {
            throw new AccessDeniedException(DenyReason.RULE_NOT_AUTHORIZED,
                    "规则不存在: " + ruleId);
        }
        Device target = deviceRegistry.find(targetDeviceId);
        if (target == null) {
            throw new IllegalArgumentException("UNKNOWN_DEVICE: 动作目标设备不存在 " + targetDeviceId);
        }
        if (!rule.householdId().equals(target.householdId())) {
            throw new AccessDeniedException(DenyReason.ACTION_TARGET_OUT_OF_SCOPE,
                    "规则 " + ruleId + "（家庭 " + rule.householdId()
                            + "）的动作目标设备 " + targetDeviceId + " 属于家庭 "
                            + target.householdId() + "，跨家庭动作被拒绝");
        }
    }
}
