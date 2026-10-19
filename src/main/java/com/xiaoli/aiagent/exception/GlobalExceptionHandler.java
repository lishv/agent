package com.xiaoli.aiagent.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.web.server.ResponseStatusException;

/** 业务、请求校验、框架异常及未知异常统一为 code / message。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> businessExceptionHandler(BusinessException error) {
        if (error.getHttpStatus().is5xxServerError()) log.error("业务执行失败，code={}", error.getCode(), error);
        return response(error.getHttpStatus().value(), ErrorResponse.from(error));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            BindException.class, ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> validationExceptionHandler(Exception error) {
        return response(400, new ErrorResponse(ErrorCode.PARAMS_ERROR.getCode(), "请检查填写的内容"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> statusExceptionHandler(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        if (status >= 500) log.error("框架处理失败，status={}", status, error);
        return response(status, ErrorResponse.of(ErrorCode.fromStatus(status)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> exceptionHandler(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse frameworkError) {
            int status = frameworkError.getStatusCode().value();
            if (status >= 500) log.error("框架处理失败，status={}", status, error);
            return response(status, ErrorResponse.of(ErrorCode.fromStatus(status)));
        }
        log.error("接口处理失败", error);
        return response(500, ErrorResponse.of(ErrorCode.SYSTEM_ERROR));
    }

    private ResponseEntity<ErrorResponse> response(int status, ErrorResponse body) {
        // SSE 开始前失败仍返回 JSON，避免协商成 text/event-stream 后无法写入错误响应。
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
