package com.github.h3yow.homeautomationenginejava.api;

import com.github.h3yow.homeautomationenginejava.engine.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误出口：越权 403、未知资源 404、语义拒绝 422，reason 字段可机读区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Dtos.ErrorBody> denied(AccessDeniedException ex) {
        // TODO 骨架
        return null;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorBody> badRequest(IllegalArgumentException ex) {
        // TODO 骨架
        return null;
    }
}
