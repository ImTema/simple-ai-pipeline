package com.simplepipeline.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

public record CodeMapping(
        @JsonProperty("provider_code") @ToolParam(description = "The provider error code") String providerCode,
        @JsonProperty("provider_message") @ToolParam(description = "Short description of the provider error") String providerMessage,
        @JsonProperty("internal_category") @ToolParam(description = "Internal decline taxonomy category") InternalCategory internalCategory,
        @ToolParam(description = "LLM certainty in the mapping") Confidence confidence,
        @ToolParam(description = "Explanation of the mapping decision") String reasoning,
        @JsonProperty("retry_strategy") @ToolParam(description = "Whether and how to retry this error") RetryStrategy retryStrategy,
        @JsonProperty("needs_human_review") @ToolParam(description = "True when confidence is LOW") boolean needsHumanReview,
        @JsonProperty("review_reason") @ToolParam(description = "Explains ambiguity when confidence is LOW, otherwise null") String reviewReason
) {}
