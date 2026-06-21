package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record IncidentAnalysis(
        @JsonProperty("incident_id") @ToolParam(description = "Incident identifier, or null if not identified") String incidentId,
        @ToolParam(description = "Short phrase classifying the failure type") String category,
        @ToolParam(description = "Structured summary of the incident") IncidentSummary summary,
        @ToolParam(description = "Up to 3 candidate root causes ranked by probability") List<Hypothesis> hypotheses,
        @JsonProperty("immediate_actions") @ToolParam(description = "Up to 2 mitigation steps to take immediately") List<ImmediateAction> immediateActions
) {}
