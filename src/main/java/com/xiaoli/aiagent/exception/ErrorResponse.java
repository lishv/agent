package com.xiaoli.aiagent.exception;

/** REST 错误响应；SSE 使用同一套安全提示，不把内部异常详情展示给用户。 */
public record ErrorResponse(int code, String message) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }

    public static ErrorResponse from(Throwable error) {
        if (error instanceof BusinessException business) {
            String message = business.getMessage();
            return new ErrorResponse(business.getCode(), message == null || message.isBlank()
                    ? ErrorCode.fromCode(business.getCode()).getMessage() : message);
        }
        return of(ErrorCode.SYSTEM_ERROR);
    }
}
