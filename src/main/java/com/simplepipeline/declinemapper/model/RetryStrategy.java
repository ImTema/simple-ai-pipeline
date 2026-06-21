package com.simplepipeline.declinemapper.model;

public enum RetryStrategy {
    NO_RETRY, RETRY_WITH_BACKOFF, RETRY_AFTER_FIX, NO_ACTION
}
