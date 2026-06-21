package com.simplepipeline.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CodeMapping(
        @JsonProperty("provider_code") String providerCode,
        @JsonProperty("provider_message") String providerMessage,
        @JsonProperty("internal_category") String internalCategory,
        String confidence,
        String reasoning,
        @JsonProperty("retry_strategy") String retryStrategy,
        @JsonProperty("needs_human_review") boolean needsHumanReview,
        @JsonProperty("review_reason") String reviewReason
) {}
