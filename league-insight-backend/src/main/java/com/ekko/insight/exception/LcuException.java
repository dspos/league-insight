package com.ekko.insight.exception;

/**
 * LCU 相关异常
 */
public class LcuException extends RuntimeException {

    public LcuException(String message) {
        super(message);
    }

    public LcuException(String message, Throwable cause) {
        super(message, cause);
    }

    public LcuException(Throwable cause) {
        super(cause);
    }
}
