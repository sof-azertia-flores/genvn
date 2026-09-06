package com.genvn.asset;

/**
 * Carries the one thing the scheduler must know: is this worth trying again?
 * 429, 5xx and timeouts are; 400/401/403/404 (bad parameters, no permission, unknown model)
 * are not, and retrying them would only burn budget.
 */
public class ImageProviderException extends Exception {

    private final boolean retryable;
    private final int statusCode;
    private final long retryAfterMillis;

    public ImageProviderException(String message, boolean retryable, int statusCode, long retryAfterMillis) {
        super(message);
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public ImageProviderException(String message, boolean retryable) {
        this(message, retryable, 0, 0);
    }

    public ImageProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = 0;
        this.retryAfterMillis = 0;
    }

    public boolean isRetryable() { return retryable; }
    public int statusCode() { return statusCode; }
    public long retryAfterMillis() { return retryAfterMillis; }

    public static boolean retryableStatus(int status) {
        return status == 429 || status == 408 || status >= 500;
    }
}
