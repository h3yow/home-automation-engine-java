package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.RejectReason;
import com.github.h3yow.homeautomationenginejava.model.Resident;
import com.github.h3yow.homeautomationenginejava.model.Role;
import org.springframework.stereotype.Component;

/**
 * 多住户授权（两类可区分的拒绝）：
 * <ol>
 *   <li>资源不属于请求者家庭：CROSS_HOME_ACCESS；</li>
 *   <li>同家庭但角色不足：INSUFFICIENT_ROLE。</li>
 * </ol>
 * 系统内部自动化动作以规则所属家庭为主体，跨设备家庭同样判 CROSS_HOME_ACCESS，
 * 即“系统触发”不享有越界特权。
 */
@Component
public class AccessControl {

    private final DeviceRegistry registry;

    public AccessControl(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param resourceHomeId 目标资源（设备/规则/记录）所属家庭
     */
    public Authorization authorize(Resident resident, String resourceHomeId, Action action) {
        if (resident == null) {
            return Authorization.deny(RejectReason.INSUFFICIENT_ROLE,
                    "unknown resident");
        }
        if (resourceHomeId == null) {
            return Authorization.deny(RejectReason.DEVICE_NOT_FOUND,
                    "resource has no home binding");
        }
        if (!resident.homeId().equals(resourceHomeId)) {
            return Authorization.deny(RejectReason.CROSS_HOME_ACCESS,
                    "resident home '" + resident.homeId()
                            + "' cannot access resource home '" + resourceHomeId + "'");
        }
        if (!roleAllows(resident.role(), action)) {
            return Authorization.deny(RejectReason.INSUFFICIENT_ROLE,
                    "role " + resident.role() + " not allowed to " + action
                            + " in home '" + resourceHomeId + "'");
        }
        return Authorization.allow();
    }

    /**
     * 自动化动作授权：规则家庭必须与目标设备家庭一致，否则视为越界。
     */
    public Authorization authorizeAutomation(String ruleHomeId, String targetDeviceId) {
        String deviceHome = registry.homeOfDevice(targetDeviceId);
        if (deviceHome == null) {
            return Authorization.deny(RejectReason.DEVICE_NOT_FOUND,
                    "target device not registered: " + targetDeviceId);
        }
        if (!deviceHome.equals(ruleHomeId)) {
            return Authorization.deny(RejectReason.CROSS_HOME_ACCESS,
                    "automation rule of home '" + ruleHomeId
                            + "' attempted device of home '" + deviceHome + "'");
        }
        return Authorization.allow();
    }

    private boolean roleAllows(Role role, Action action) {
        return switch (action) {
            case READ_DEVICE, READ_LOG -> true; // 同家庭的任何角色都可读
            case WRITE_DEVICE -> role == Role.OWNER || role == Role.MEMBER;
            case MANAGE_RULE -> role == Role.OWNER;
        };
    }

    public enum Action {
        READ_DEVICE, WRITE_DEVICE, MANAGE_RULE, READ_LOG
    }

    public record Authorization(boolean allowed, RejectReason reason, String detail) {
        public static Authorization allow() { return new Authorization(true, null, "allowed"); }
        public static Authorization deny(RejectReason r, String d) {
            return new Authorization(false, r, d);
        }
    }
}
