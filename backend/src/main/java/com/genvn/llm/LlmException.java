package com.genvn.llm;

/**
 * A failed model call. Carries the provider's HTTP status (0 when there was none) and whether
 * repeating the same request could reasonably succeed: a timeout, a 429 or a 5xx can; a 400,
 * 401, 403 or 404 cannot, and retrying those only costs time.
 */
public class LlmException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, 0, true);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, 0, true);
    }

    public LlmException(String message, int statusCode) {
        this(message, null, statusCode, retryableStatus(statusCode));
    }

    public LlmException(String message, Throwable cause, int statusCode, boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** No status (transport failure, timeout), 408, 429 and 5xx are worth one more try. */
    public static boolean retryableStatus(int status) {
        return status == 0 || status == 408 || status == 429 || status >= 500;
    }
}
