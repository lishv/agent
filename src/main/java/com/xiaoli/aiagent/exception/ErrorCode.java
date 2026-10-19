package com.xiaoli.aiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 统一业务错误码；HTTP 状态保留登录、限流、任务结束等协议语义。 */
@Getter
public enum ErrorCode {
    SUCCESS(0, "ok", HttpStatus.OK),
    PARAMS_ERROR(40000, "请求参数错误", HttpStatus.BAD_REQUEST),
    NOT_LOGIN_ERROR(40100, "请先登录", HttpStatus.UNAUTHORIZED),
    NO_AUTH_ERROR(40101, "无权限", HttpStatus.FORBIDDEN),
    FORBIDDEN_ERROR(40300, "禁止访问", HttpStatus.FORBIDDEN),
    NOT_FOUND_ERROR(40400, "请求数据不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(40500, "请求方式不正确", HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT_ERROR(40900, "当前状态不允许此操作", HttpStatus.CONFLICT),
    ACCOUNT_CHANGED(40901, "账号已切换，请重新读取登录状态", HttpStatus.CONFLICT),
    TASK_ENDED(41000, "任务已结束，请重新发起", HttpStatus.GONE),
    UNSUPPORTED_MEDIA_TYPE(41500, "请求内容格式不正确", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOO_MANY_REQUESTS(42900, "操作过于频繁，请稍后重试", HttpStatus.TOO_MANY_REQUESTS),
    SYSTEM_ERROR(50000, "服务暂时不可用，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    OPERATION_ERROR(50001, "操作失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, "服务暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    TASK_TIMEOUT(50400, "等待用户回答超时，请重新发起任务", HttpStatus.GATEWAY_TIMEOUT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) if (value.code == code) return value;
        return SYSTEM_ERROR;
    }

    public static ErrorCode fromStatus(int status) {
        for (ErrorCode value : values()) if (value.httpStatus.value() == status) return value;
        return status >= 400 && status < 500 ? PARAMS_ERROR : SYSTEM_ERROR;
    }
}
