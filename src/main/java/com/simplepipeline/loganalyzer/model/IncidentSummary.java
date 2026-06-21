package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record IncidentSummary(
        String description,
        @JsonProperty("affected_adapters") List<String> affectedAdapters,
        @JsonProperty("affected_order_types") List<String> affectedOrderTypes,
        @JsonProperty("fault_layer") String faultLayer,
        String severity,
        @JsonProperty("severity_reasoning") String severityReasoning,
        @JsonProperty("blast_radius") String blastRadius
) {}
