package com.simplepipeline.declinemapper.model;

public enum InternalCategory {
    SYSTEM_MALFUNCTION("Internal system error, provider infrastructure failure. Retryable with backoff."),
    COMMON_DECLINE("Generic bank/provider decline (insufficient funds, limit, expired card). Retryable depends on sub-code."),
    ANTIFRAUD("Transaction blocked by fraud detection. Not retryable."),
    BAD_DATA_PROVIDED("Invalid input data (wrong account, invalid currency, malformed fields). Not retryable."),
    CANCELLED_BY_CUSTOMER("Customer cancelled or abandoned the transaction. Not retryable."),
    PROVIDER_LIMIT("Provider rate limit or quota reached. Retryable after cooldown."),
    AUTHENTICATION_FAILURE("Invalid credentials, expired token, signature mismatch. Not retryable.");

    public final String description;

    InternalCategory(String description) {
        this.description = description;
    }
}
