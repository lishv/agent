package com.xiaoli.aiagent.exception;

/** 条件成立时抛出异常，统一业务校验写法。 */
public final class ThrowUtils {
    private ThrowUtils() { }

    public static void throwIf(boolean condition, RuntimeException exception) {
        if (condition) throw exception;
    }

    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) throw new BusinessException(errorCode);
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) throw new BusinessException(errorCode, message);
    }
}
