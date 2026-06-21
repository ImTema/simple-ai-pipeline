package com.simplepipeline.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MappingSummary(
        @JsonProperty("total_codes") int totalCodes,
        @JsonProperty("high_confidence") int highConfidence,
        @JsonProperty("needs_review") int needsReview,
        int unmapped
) {}
