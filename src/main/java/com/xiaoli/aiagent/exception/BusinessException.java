package com.xiaoli.aiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 业务失败统一携带错误码；根因留在服务端日志中。 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final HttpStatus httpStatus;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = ErrorCode.fromCode(code).getHttpStatus();
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }
}
