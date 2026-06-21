package com.simplepipeline.declinemapper.model;

public enum RetryStrategy {
    NO_RETRY("Terminal error, do not retry."),
    RETRY_WITH_BACKOFF("Transient error, retry with exponential backoff."),
    RETRY_AFTER_FIX("Retryable only after merchant/customer/developer fixes something."),
    NO_ACTION("Not a real error (e.g. idempotency guard).");

    public final String description;

    RetryStrategy(String description) {
        this.description = description;
    }
}
