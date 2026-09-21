package com.github.h3yow.homeautomationenginejava.engine;

import com.github.h3yow.homeautomationenginejava.model.DenyReason;

/**
 * 越权访问异常：携带可区分的拒绝原因。
 */
public class AccessDeniedException extends RuntimeException {
    private final DenyReason reason;

    public AccessDeniedException(DenyReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public DenyReason reason() {
        return reason;
    }
}
