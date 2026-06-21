package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record Hypothesis(
        @ToolParam(description = "Short title of the candidate root cause") String title,
        @ToolParam(description = "Evidence and reasoning supporting this hypothesis") String reasoning,
        @ToolParam(description = "Likelihood of this hypothesis being the root cause") Probability probability,
        @JsonProperty("next_steps") @ToolParam(description = "Investigation steps to validate or rule out this hypothesis") List<NextStep> nextSteps
) {}
