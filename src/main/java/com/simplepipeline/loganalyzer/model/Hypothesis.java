package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Hypothesis(
        String title,
        String reasoning,
        String probability,
        @JsonProperty("next_steps") List<NextStep> nextSteps
) {}
