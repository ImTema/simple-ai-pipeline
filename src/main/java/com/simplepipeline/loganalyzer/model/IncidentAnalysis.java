package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record IncidentAnalysis(
        @JsonProperty("incident_id") String incidentId,
        String category,
        IncidentSummary summary,
        List<Hypothesis> hypotheses,
        @JsonProperty("immediate_actions") List<ImmediateAction> immediateActions
) {}
