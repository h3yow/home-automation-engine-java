package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误出口：
 * - 403 越权（reason 为 DenyReason，可机读区分跨家庭/设备/规则/动作越界等）；
 * - 404 未知设备/规则；
 * - 422 语义拒绝（模型/参数/条件不合法）；
 * - 400 请求体无法解析。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Dtos.ErrorBody> denied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new Dtos.ErrorBody("ACCESS_DENIED", ex.reason().name(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorBody> illegal(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        HttpStatus status;
        String reason;
        if (message.startsWith("UNKNOWN_DEVICE")) {
            status = HttpStatus.NOT_FOUND;
            reason = "UNKNOWN_DEVICE";
        } else if (message.startsWith("UNKNOWN_RULE")) {
            status = HttpStatus.NOT_FOUND;
            reason = "UNKNOWN_RULE";
        } else if (message.startsWith("UNKNOWN_DRY_RUN")) {
            status = HttpStatus.NOT_FOUND;
            reason = "UNKNOWN_DRY_RUN";
        } else if (message.startsWith("UNSUPPORTED_MODEL_VERSION")) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            reason = "UNSUPPORTED_MODEL_VERSION";
        } else {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            reason = "INVALID_REQUEST";
        }
        return ResponseEntity.status(status)
                .body(new Dtos.ErrorBody("REQUEST_REJECTED", reason, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorBody> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorBody("BAD_REQUEST", "MALFORMED_JSON",
                        "请求体不是合法 JSON"));
    }
}
